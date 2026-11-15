package br.com.minhaobra.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ProjectionCalculator {
    private static final BigDecimal ANNUAL_PERCENT_TO_MONTHLY_FACTOR = BigDecimal.valueOf(1_200);
    private static final RecordedCharge NO_RECORDED_CHARGE = new RecordedCharge(false, null, 0, true);

    private ProjectionCalculator() {
    }

    public static Result calculate(Scenario scenario, Map<YearMonth, RecordedCharge> recorded) {
        long months = scenario.currentMonth().until(scenario.deliveryMonth(), ChronoUnit.MONTHS) + 1;
        if (months <= 0) {
            return new Result(List.of(), 0, 0L, 0, 0L);
        }

        long remaining = scenario.financedCents() - scenario.releasedCents();
        long equalRelease = remaining / months;
        long balance = scenario.releasedCents();
        long knownTotal = 0;
        long grossKnownTotal = 0;
        boolean complete = true;
        boolean grossComplete = true;
        List<Row> rows = new ArrayList<>();
        YearMonth month = scenario.currentMonth();

        for (long index = 1; index <= months; index++, month = month.plusMonths(1)) {
            long release = index == months ? scenario.financedCents() - balance : equalRelease;
            RecordedCharge charge = recorded.getOrDefault(month, NO_RECORDED_CHARGE);
            Row row = estimateMonth(scenario, charge, month, balance, release);
            balance = row.releasedCents();
            rows.add(row);
            knownTotal += row.knownSubtotalCents();
            grossKnownTotal += row.grossKnownSubtotalCents();
            complete &= row.totalCents() != null;
            grossComplete &= row.grossCents() != null;
        }
        return new Result(List.copyOf(rows), knownTotal, complete ? knownTotal : null,
                grossKnownTotal, grossComplete ? grossKnownTotal : null);
    }

    private static Row estimateMonth(Scenario scenario, RecordedCharge charge, YearMonth month, long balance,
            long release) {
        long closingBalance = balance + release;
        Long tr = correction(balance, scenario.monthlyTr());
        Long interest = tr == null ? null : interest(balance + tr, scenario.annualNominalRate());
        long knownCharges = orZero(tr) + orZero(interest) + orZero(scenario.insuranceCents())
                + orZero(scenario.feesCents());
        boolean completeGross = tr != null && scenario.insuranceCents() != null && scenario.feesCents() != null;
        Long gross = completeGross ? knownCharges : null;
        if (charge.settled()) {
            return new Row(month, closingBalance, release, true, null, null, null, null, 0, 0L,
                    charge.paidCents(), true, balance, knownCharges, gross);
        }

        long remainingKnown = Math.max(0, knownCharges - charge.paidTowardEstimateCents());
        boolean complete = completeGross && charge.paymentAllocationKnown();
        // A liberação entra no saldo para a próxima cobrança, como no simulador original.
        return new Row(month, closingBalance, release, false, tr, interest, scenario.insuranceCents(),
                scenario.feesCents(), remainingKnown, complete ? remainingKnown : null, charge.paidCents(),
                charge.paymentAllocationKnown(), balance, knownCharges, gross);
    }

    private static Long correction(long balance, BigDecimal monthlyTr) {
        if (balance == 0) {
            return 0L;
        }
        if (monthlyTr == null) {
            return null;
        }
        return monthlyTr.multiply(BigDecimal.valueOf(balance)).movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long interest(long correctedBalance, BigDecimal nominalRate) {
        return nominalRate.multiply(BigDecimal.valueOf(correctedBalance))
                .divide(ANNUAL_PERCENT_TO_MONTHLY_FACTOR, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    public record Scenario(YearMonth currentMonth, YearMonth deliveryMonth, long financedCents, long releasedCents,
            BigDecimal annualNominalRate, BigDecimal monthlyTr, Long insuranceCents, Long feesCents) {
    }

    public record RecordedCharge(boolean settled, Long paidCents, long paidTowardEstimateCents,
            boolean paymentAllocationKnown) {
    }

    public record Row(YearMonth month, long releasedCents, long releaseCents, boolean settled,
            Long trCents, Long interestCents, Long insuranceCents, Long feesCents, long knownSubtotalCents,
            Long totalCents, Long caixaPaidCents, boolean paymentAllocationKnown,
            long baseBalanceCents, long grossKnownSubtotalCents, Long grossCents) {
    }

    public record Result(List<Row> rows, long knownSubtotalCents, Long totalCents,
            long grossKnownSubtotalCents, Long grossCents) {
    }
}
