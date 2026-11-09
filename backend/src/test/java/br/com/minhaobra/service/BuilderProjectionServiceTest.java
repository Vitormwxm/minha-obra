package br.com.minhaobra.service;

import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Status;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.BuilderProjection;
import br.com.minhaobra.dto.Finance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuilderProjectionServiceTest {
    private final FinanceService finance = mock(FinanceService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-12-01T01:00:00Z"), ZoneOffset.UTC);
    private final BuilderProjectionService service = new BuilderProjectionService(finance, clock);
    private final SessionPrincipal principal = new SessionPrincipal(UUID.randomUUID(), 0, false, 0);

    @Test
    void usesLocalMonthAndOnlyTheOwnersBuilderPayments() {
        var paid = new Finance.MonthView("2026-11", 1, "2026-11-15", "2026-11-12",
                Status.PAID, Status.OPEN, null, null, null,
                List.of(new Finance.PaymentView(Kind.CAIXA, "2026-11-10", 900, null),
                        new Finance.PaymentView(Kind.CONSTRUTORA, "2026-11-20", 300, 50L)),
                false, 900L, 300L, 1_200L, 900, 300, 1_200, null, null, null, null, null);
        when(finance.ledger(principal)).thenReturn(ledger(List.of(paid)));

        var result = service.project(principal, new BuilderProjection.Input(
                List.of(installment("2026-12"), installment("2026-11")), null));

        assertEquals(List.of("2026-11", "2026-12"), result.rows().stream().map(BuilderProjection.Row::month).toList());
        assertEquals(750L, result.rows().getFirst().remainingCents());
        assertEquals(300L, result.rows().getFirst().paidCents());
        assertEquals(1_750L, result.remainingCents());
        verify(finance).ledger(principal);
        verifyNoMoreInteractions(finance);
    }

    @Test
    void rejectsMissingFinancingAndInvalidProjectionMonths() {
        var input = new BuilderProjection.Input(List.of(installment("2026-12")), null);
        when(finance.ledger(principal)).thenReturn(new Finance.Ledger(null, List.of(), List.of()));
        assertEquals("FINANCING_NOT_FOUND", assertThrows(ApiException.class, () -> service.project(principal, input)).code());

        when(finance.ledger(principal)).thenReturn(ledger(List.of()));
        var duplicate = new BuilderProjection.Input(List.of(installment("2026-12"), installment("2026-12")), null);
        assertEquals("BUILDER_PROJECTION_DUPLICATE_MONTH",
                assertThrows(ApiException.class, () -> service.project(principal, duplicate)).code());
        var late = new BuilderProjection.Input(List.of(installment("2027-02")), null);
        assertEquals("BUILDER_PROJECTION_MONTH",
                assertThrows(ApiException.class, () -> service.project(principal, late)).code());
    }

    private static Finance.Ledger ledger(List<Finance.MonthView> months) {
        var financing = new Finance.FinancingView(UUID.randomUUID(), 1, 100_000, 50_000, "8", "2027-01-20",
                "2026-11", null);
        return new Finance.Ledger(financing, months, List.of());
    }

    private static BuilderProjection.Installment installment(String month) {
        return new BuilderProjection.Installment(month, new BigDecimal("1000"), false);
    }
}
