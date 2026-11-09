package br.com.minhaobra.domain;

import br.com.minhaobra.domain.ProjectionCalculator.RecordedCharge;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BuilderProjectionCalculator {
    public static final int MAX_INSTALLMENTS = 120;
    // Keep the sum of all rows in the exact integer range of the JSON consumer.
    private static final BigDecimal MAX_PROJECTED_CENTS = BigDecimal.valueOf(9_007_199_254_740_991L / MAX_INSTALLMENTS);
    private static final RecordedCharge NO_RECORDED_CHARGE = new RecordedCharge(false, null, 0, true);

    private BuilderProjectionCalculator() {
    }

    public static Result calculate(List<Installment> installments, Incc incc, Map<YearMonth, RecordedCharge> recorded) {
        List<Row> rows = new ArrayList<>(installments.size());
        long knownSubtotal = 0;
        boolean complete = true;
        for (Installment installment : installments) {
            var charge = recorded.getOrDefault(installment.month(), NO_RECORDED_CHARGE);
            Row row = project(installment, incc, charge);
            rows.add(row);
            knownSubtotal += row.knownSubtotalCents();
            complete &= row.remainingCents() != null;
        }
        return new Result(List.copyOf(rows), complete ? knownSubtotal : null, knownSubtotal);
    }

    private static Row project(Installment installment, Incc incc, RecordedCharge charge) {
        IndexStatus status = indexStatus(installment, incc);
        if (charge.settled()) {
            return new Row(installment.month(), installment.baseCents(), null, 0L, charge.paidCents(),
                    true, status, 0, true);
        }
        if (status == IndexStatus.PENDING) {
            return new Row(installment.month(), installment.baseCents(), installment.baseCents(), null,
                    charge.paidCents(), false, status, 0, charge.paymentAllocationKnown());
        }
        long projected = status == IndexStatus.PROJECTED ? adjusted(installment, incc) : installment.baseCents();
        long knownSubtotal = Math.max(0, projected - charge.paidTowardEstimateCents());
        return new Row(installment.month(), installment.baseCents(), projected,
                charge.paymentAllocationKnown() ? knownSubtotal : null, charge.paidCents(), false, status,
                knownSubtotal, charge.paymentAllocationKnown());
    }

    private static IndexStatus indexStatus(Installment installment, Incc incc) {
        if (!installment.indexed()) {
            return IndexStatus.FIXED;
        }
        return incc == null || incc.monthlyRate() == null ? IndexStatus.PENDING : IndexStatus.PROJECTED;
    }

    private static long adjusted(Installment installment, Incc incc) {
        long elapsed = ChronoUnit.MONTHS.between(incc.baseMonth(), installment.month());
        // The contractual anniversary determines when the value changes. The lag
        // determines how many index months are available at that anniversary.
        long lastAdjustment = elapsed / incc.intervalMonths() * incc.intervalMonths();
        int indexMonths = Math.toIntExact(Math.max(0, lastAdjustment - incc.lagMonths()));
        BigDecimal factor = BigDecimal.ONE.add(incc.monthlyRate().movePointLeft(2));
        BigDecimal cents = BigDecimal.valueOf(installment.baseCents())
                .multiply(factor.pow(indexMonths, MathContext.DECIMAL128))
                .setScale(0, RoundingMode.HALF_UP);
        if (cents.compareTo(MAX_PROJECTED_CENTS) > 0) {
            throw new ArithmeticException("Builder projection exceeds the supported amount.");
        }
        return cents.longValueExact();
    }

    public enum IndexStatus { FIXED, PENDING, PROJECTED }

    public record Installment(YearMonth month, long baseCents, boolean indexed) {
    }

    public record Incc(YearMonth baseMonth, int intervalMonths, int lagMonths, BigDecimal monthlyRate) {
    }

    public record Row(YearMonth month, long baseCents, Long projectedCents, Long remainingCents,
            Long paidCents, boolean settled, IndexStatus indexStatus, long knownSubtotalCents,
            boolean paymentAllocationKnown) {
    }

    public record Result(List<Row> rows, Long remainingCents, long knownSubtotalCents) {
    }
}
