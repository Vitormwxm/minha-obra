package br.com.minhaobra.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

class BcbTrServiceTest {
    private final HttpClient http = mock(HttpClient.class);
    private final BcbTrService service = new BcbTrService(http, JsonMapper.builder().build(),
            Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void selectsTheExactPeriodWhenSgsPublishesSeveralRecordsForOneReferenceDate() {
        serve(200, """
                [{"data":"01/03/2025","dataFim":"29/03/2025","valor":"0.0771"},
                 {"data":"01/03/2025","dataFim":"30/03/2025","valor":"0.0771"},
                 {"data":"01/03/2025","dataFim":"31/03/2025","valor":"0.0771"},
                 {"data":"01/03/2025","dataFim":"01/04/2025","valor":"0.1092"}]
                """);

        assertEquals(new BigDecimal("0.1092"), lookup("2025-03-01", "2025-04-01"));
        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(sent.capture(), ArgumentMatchers.<BodyHandler<byte[]>>any());
        HttpRequest request = sent.getValue();
        assertEquals("GET", request.method());
        assertEquals("https://api.bcb.gov.br/dados/serie/bcdata.sgs.226/dados"
                + "?formato=json&dataInicial=01/03/2025&dataFinal=01/03/2025", request.uri().toString());
        assertEquals(Duration.ofSeconds(3), request.timeout().orElseThrow());
    }

    @Test
    void checksThePublishedEndDateInsteadOfRoundingToTheEndOfTheFollowingMonth() {
        serve(200, """
                [{"data":"31/01/2025","dataFim":"01/03/2025","valor":"0.1630"}]
                """);
        assertEquals(new BigDecimal("0.1630"), lookup("2025-01-31", "2025-03-01"));
        assertTrue(service.lookup(LocalDate.parse("2025-01-31"), LocalDate.parse("2025-02-28")).isEmpty());
    }

    @Test
    void distinguishesAnOfficialZeroFromAnUnverifiedRate() {
        serve(200, """
                [{"data":"01/01/2020","dataFim":"01/02/2020","valor":"0.0000"}]
                """);
        assertEquals(new BigDecimal("0.0000"), lookup("2020-01-01", "2020-02-01"));
    }

    @Test
    void declinesMissingDatesAmbiguousMatchesAndInvalidResponses() {
        var start = LocalDate.parse("2025-03-01");
        var end = LocalDate.parse("2025-04-01");
        for (String body : new String[]{"", "[]", "<html>BCB indisponível</html>",
                "[{\"data\":\"01/03/2025\",\"valor\":\"0.0771\"}]",
                """
                [{"data":"01/03/2025","dataFim":"01/04/2025","valor":"0.1092"},
                 {"data":"01/03/2025","dataFim":"01/04/2025","valor":"0.0771"}]
                """, """
                [{"data":"01/03/2025","dataFim":"01/04/2025","valor":"-0.0771"}]
                """}) {
            serve(200, body);
            assertTrue(service.lookup(start, end).isEmpty());
        }
        serve(503, "unavailable");
        assertTrue(service.lookup(start, end).isEmpty());
        when(http.sendAsync(any(HttpRequest.class), ArgumentMatchers.<BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("offline")));
        assertTrue(service.lookup(start, end).isEmpty());
    }

    @Test
    void skipsInvalidPeriodsBeforeMakingAnExternalRequest() {
        var date = LocalDate.parse("2025-03-01");
        var future = LocalDate.parse("2027-01-01");
        assertTrue(service.lookup(date, date).isEmpty());
        assertTrue(service.lookup(null, date).isEmpty());
        assertTrue(service.lookup(LocalDate.parse("1990-01-01"), date).isEmpty());
        assertTrue(service.lookup(future, future.plusMonths(1)).isEmpty());
        verifyNoInteractions(http);
    }

    private BigDecimal lookup(String start, String end) {
        return service.lookup(LocalDate.parse(start), LocalDate.parse(end)).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void serve(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(any(HttpRequest.class), ArgumentMatchers.<BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
}
