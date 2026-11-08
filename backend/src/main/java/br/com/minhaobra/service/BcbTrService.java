package br.com.minhaobra.service;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Service
public class BcbTrService {
    private static final String ENDPOINT = "https://api.bcb.gov.br/dados/serie/bcdata.sgs.226/dados"
            + "?formato=json&dataInicial=%s&dataFinal=%s";
    private static final LocalDate SERIES_START = LocalDate.of(1991, 2, 1);
    private static final ZoneId BRAZIL_ZONE = ZoneId.of("America/Fortaleza");
    private static final DateTimeFormatter SGS_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT);
    private static final Pattern SGS_PERCENT = Pattern.compile("[0-9]{1,3}\\.[0-9]{4}");
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_BODY_BYTES = 4096;

    private final HttpClient http;
    private final ObjectReader json;
    private final Clock clock;
    private final Semaphore inFlight = new Semaphore(2);

    @Autowired
    public BcbTrService(ObjectMapper json, Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500))
                .followRedirects(HttpClient.Redirect.NEVER).build(), json, clock);
    }

    BcbTrService(HttpClient http, ObjectMapper json, Clock clock) {
        this.http = http;
        this.json = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.clock = clock;
    }

    /** Returns the SGS 226 monthly percentage only for the exact published dates. */
    public Optional<BigDecimal> lookup(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)
                || startDate.isBefore(SERIES_START) || startDate.isAfter(LocalDate.now(clock.withZone(BRAZIL_ZONE)))
                || !inFlight.tryAcquire()) {
            return Optional.empty();
        }
        try {
            String start = SGS_DATE.format(startDate);
            String end = SGS_DATE.format(endDate);
            // SGS filters by data, and a single date can have several distinct dataFim values.
            var request = HttpRequest.newBuilder(URI.create(ENDPOINT.formatted(start, start)))
                    .header("Accept", "application/json").timeout(TIMEOUT).GET().build();
            var response = http.sendAsync(request,
                    info -> BodySubscribers.limiting(BodySubscribers.ofByteArray(), MAX_BODY_BYTES));
            try {
                var result = response.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return result.statusCode() == 200 ? parse(result.body(), start, end) : Optional.empty();
            } catch (InterruptedException interrupted) {
                response.cancel(true);
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (TimeoutException timeout) {
                response.cancel(true);
                return Optional.empty();
            } catch (CancellationException | ExecutionException | JacksonException unavailable) {
                return Optional.empty();
            }
        } finally {
            inFlight.release();
        }
    }

    private Optional<BigDecimal> parse(byte[] body, String start, String end) {
        JsonNode rows = json.readTree(body);
        if (!rows.isArray()) {
            return Optional.empty();
        }
        BigDecimal match = null;
        for (JsonNode row : rows) {
            if (start.equals(row.path("data").stringValue(null)) && end.equals(row.path("dataFim").stringValue(null))) {
                String value = row.path("valor").stringValue(null);
                if (match != null || value == null || !SGS_PERCENT.matcher(value).matches()) {
                    return Optional.empty();
                }
                match = new BigDecimal(value);
                if (match.compareTo(MAX_PERCENT) > 0) {
                    return Optional.empty();
                }
            }
        }
        return Optional.ofNullable(match);
    }

    @PreDestroy
    void stop() {
        http.shutdownNow();
    }
}
