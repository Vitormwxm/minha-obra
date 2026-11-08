package br.com.minhaobra.domain;

import br.com.minhaobra.domain.ProjectionCalculator.RecordedCharge;
import br.com.minhaobra.domain.ProjectionCalculator.Row;
import br.com.minhaobra.domain.ProjectionCalculator.Scenario;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionCalculatorTest {
    private static final YearMonth START = YearMonth.of(2026, 11);

    @Test
    void matchesLegacyCalculatorWhenReleaseEntersNextMonthsBase() {
        // calculator.js fixture with releaseTiming=end, the same nominal rate and monthly charges.
        var scenario = new Scenario(START, START.plusMonths(2), 10_005, 10_000,
                new BigDecimal("12"), new BigDecimal("10"), 210L, 40L);
        var result = ProjectionCalculator.calculate(scenario, Map.of());

        assertEquals(List.of(1L, 1L, 3L), result.rows().stream().map(Row::releaseCents).toList());
        assertEquals(List.of(10_001L, 10_002L, 10_005L), result.rows().stream().map(Row::releasedCents).toList());
        assertEquals(List.of(1_000L, 1_000L, 1_000L), result.rows().stream().map(Row::trCents).toList());
        assertEquals(List.of(110L, 110L, 110L), result.rows().stream().map(Row::interestCents).toList());
        assertEquals(List.of(1_360L, 1_360L, 1_360L), result.rows().stream().map(Row::totalCents).toList());
        assertEquals(4_080L, result.knownSubtotalCents());
        assertEquals(4_080L, result.totalCents());
    }

    @Test
    void settledRowsKeepScheduledReleasesAndHaveNoEstimatedCharges() {
        var scenario = new Scenario(START, START.plusMonths(2), 10_300, 10_000,
                new BigDecimal("12"), null, null, null);
        var settled = Map.of(START, new RecordedCharge(true, 240L, 200, true),
                START.plusMonths(1), new RecordedCharge(true, 0L, 0, true),
                START.plusMonths(2), new RecordedCharge(true, 300L, 300, true));
        var result = ProjectionCalculator.calculate(scenario, settled);

        assertEquals(List.of(100L, 100L, 100L), result.rows().stream().map(Row::releaseCents).toList());
        assertEquals(10_300L, result.rows().getLast().releasedCents());
        assertEquals(List.of(240L, 0L, 300L), result.rows().stream().map(Row::caixaPaidCents).toList());
        assertTrue(result.rows().stream().allMatch(Row::settled));
        assertTrue(result.rows().stream().allMatch(row -> row.trCents() == null && row.totalCents() == 0));
        assertEquals(0L, result.totalCents());
    }

    @Test
    void retainsKnownChargesWithUnknownAssumptionsAndSubtractsPartialPaymentsOnce() {
        var scenario = new Scenario(START, START.plusMonths(1), 100_000, 100_000,
                new BigDecimal("12"), null, 3_000L, null);
        var result = ProjectionCalculator.calculate(scenario, Map.of(START, new RecordedCharge(false, 2_500L, 2_000, true)));
        Row first = result.rows().getFirst();

        assertNull(first.trCents());
        assertNull(first.interestCents());
        assertNull(first.feesCents());
        assertEquals(3_000L, first.insuranceCents());
        assertEquals(2_500L, first.caixaPaidCents());
        assertEquals(1_000L, first.knownSubtotalCents());
        assertNull(first.totalCents());
        assertEquals(4_000L, result.knownSubtotalCents());
        assertNull(result.totalCents());
    }

    @Test
    void capsRemainingEstimateAtZeroWithoutMarkingAnOpenMonthSettled() {
        var scenario = new Scenario(START, START, 1_000, 500,
                new BigDecimal("12"), BigDecimal.ZERO, 50L, 0L);
        var result = ProjectionCalculator.calculate(scenario, Map.of(START, new RecordedCharge(false, 500L, 500, true)));
        Row row = result.rows().getFirst();

        assertEquals(500L, row.releaseCents());
        assertEquals(5L, row.interestCents());
        assertEquals(0L, row.totalCents());
        assertFalse(row.settled());
        assertEquals(0L, result.totalCents());
    }

    @Test
    void zeroBalanceHasNoInterestOrCorrectionEvenWhenTrIsUnknown() {
        var scenario = new Scenario(START, START.plusMonths(1), 2, 0,
                new BigDecimal("12"), null, 0L, 0L);
        var result = ProjectionCalculator.calculate(scenario, Map.of());
        Row first = result.rows().getFirst();

        assertEquals(1L, first.releaseCents());
        assertEquals(0L, first.trCents());
        assertEquals(0L, first.interestCents());
        assertEquals(0L, first.totalCents());
        assertNull(result.rows().getLast().totalCents());
        assertNull(result.totalCents());
    }

    @Test
    void unallocatedPartialPaymentLeavesTheTotalUnknownEvenWithAllAssumptions() {
        var scenario = new Scenario(START, START, 100_000, 100_000,
                new BigDecimal("12"), BigDecimal.ZERO, 3_000L, 0L);
        var result = ProjectionCalculator.calculate(scenario, Map.of(START, new RecordedCharge(false, 2_500L, 2_500, false)));

        assertEquals(1_500L, result.knownSubtotalCents());
        assertNull(result.totalCents());
        assertFalse(result.rows().getFirst().paymentAllocationKnown());
    }

    @Test
    void deliveryBeforeCurrentMonthHasNoProjectedCharges() {
        var scenario = new Scenario(START, START.minusMonths(1), 10_000, 5_000,
                new BigDecimal("12"), null, null, null);
        var result = ProjectionCalculator.calculate(scenario, Map.of());

        assertTrue(result.rows().isEmpty());
        assertEquals(0L, result.knownSubtotalCents());
        assertEquals(0L, result.totalCents());
    }
}
