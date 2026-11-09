package br.com.minhaobra.domain;

import br.com.minhaobra.domain.BuilderProjectionCalculator.Incc;
import br.com.minhaobra.domain.BuilderProjectionCalculator.IndexStatus;
import br.com.minhaobra.domain.BuilderProjectionCalculator.Installment;
import br.com.minhaobra.domain.ProjectionCalculator.RecordedCharge;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderProjectionCalculatorTest {
    private static final YearMonth BASE = YearMonth.of(2026, 1);

    @Test
    void compoundsEligibleIndexMonthsAndHoldsTheValueUntilTheNextAdjustment() {
        var incc = new Incc(BASE, 3, 1, new BigDecimal("1"));
        var result = BuilderProjectionCalculator.calculate(List.of(
                new Installment(BASE.plusMonths(2), 10_000, true),
                new Installment(BASE.plusMonths(3), 10_000, true),
                new Installment(BASE.plusMonths(5), 10_000, true),
                new Installment(BASE.plusMonths(6), 10_000, true),
                new Installment(BASE.plusMonths(7), 10_000, false)), incc, Map.of());
        var rows = result.rows();
        assertEquals(10_000L, rows.get(0).projectedCents());
        assertEquals(10_201L, rows.get(1).projectedCents());
        assertEquals(10_201L, rows.get(2).projectedCents());
        assertEquals(10_510L, rows.get(3).projectedCents());
        assertEquals(10_000L, rows.get(4).projectedCents());
        assertEquals(IndexStatus.PROJECTED, rows.get(0).indexStatus());
        assertEquals(IndexStatus.FIXED, rows.get(4).indexStatus());
        assertEquals(50_912L, result.remainingCents());
    }

    @Test
    void handlesNegativeRatesAndRoundsOnceToCents() {
        var incc = new Incc(BASE, 1, 0, new BigDecimal("-0.5"));
        var result = BuilderProjectionCalculator.calculate(List.of(
                new Installment(BASE.plusMonths(1), 100, true),
                new Installment(BASE.plusMonths(2), 100, true)), incc, Map.of());
        assertEquals(100L, result.rows().get(0).projectedCents());
        assertEquals(99L, result.rows().get(1).projectedCents());
        assertEquals(199L, result.remainingCents());
    }

    @Test
    void missingRateShowsUnadjustedBaseWithoutTreatingItAsAMinimum() {
        var incc = new Incc(BASE, 1, 0, null);
        var result = BuilderProjectionCalculator.calculate(List.of(
                new Installment(BASE, 1_000, false),
                new Installment(BASE.plusMonths(1), 2_000, true)), incc, Map.of());
        var pending = result.rows().get(1);
        assertEquals(2_000L, pending.projectedCents());
        assertEquals(IndexStatus.PENDING, pending.indexStatus());
        assertNull(pending.remainingCents());
        assertEquals(0, pending.knownSubtotalCents());
        assertEquals(1_000L, result.knownSubtotalCents());
        assertNull(result.remainingCents());
    }

    @Test
    void prioritizesActualPaymentsAndMarksUncertainAllocation() {
        var result = BuilderProjectionCalculator.calculate(List.of(
                new Installment(BASE, 1_000, true),
                new Installment(BASE.plusMonths(1), 2_000, false),
                new Installment(BASE.plusMonths(2), 3_000, false)), null,
                Map.of(BASE, new RecordedCharge(true, 1_250L, 1_250L, true),
                       BASE.plusMonths(1), new RecordedCharge(false, 1_100L, 1_000L, true),
                       BASE.plusMonths(2), new RecordedCharge(false, 1_400L, 1_400L, false)));
        var rows = result.rows();
        assertEquals(1_250L, rows.get(0).paidCents());
        assertNull(rows.get(0).projectedCents());
        assertEquals(0L, rows.get(0).remainingCents());
        assertEquals(1_000L, rows.get(1).remainingCents());
        assertNull(rows.get(2).remainingCents());
        assertFalse(rows.get(2).paymentAllocationKnown());
        assertEquals(1_600L, rows.get(2).knownSubtotalCents());
        assertEquals(2_600L, result.knownSubtotalCents());
        assertNull(result.remainingCents());
    }

    @Test
    void rejectsValuesThatExceedTheJsonIntegerRangeAtFullCapacity() {
        var incc = new Incc(BASE, 1, 0, new BigDecimal("100"));
        assertThrows(ArithmeticException.class, () -> BuilderProjectionCalculator.calculate(
                List.of(new Installment(BASE.plusYears(20), 10_000_000_000L, true)), incc, Map.of()));
    }
}
