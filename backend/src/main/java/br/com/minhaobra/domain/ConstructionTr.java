package br.com.minhaobra.domain;

import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Payment;
import br.com.minhaobra.domain.PaymentMonth.Status;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public final class ConstructionTr {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal ANNUAL_PERCENT_TO_MONTHLY = BigDecimal.valueOf(1200);
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);

    private ConstructionTr() {
    }

    public static Optional<BigDecimal> estimate(PaymentMonth month, List<Payment> payments, BigDecimal annualRate) {
        if (month.caixaStatus() != Status.PAID || month.releasedCents() == null || month.releasedCents() == 0
                || month.insuranceCents() == null || month.feesCents() == null) {
            return Optional.empty();
        }

        List<Payment> caixa = payments.stream().filter(payment -> payment.kind() == Kind.CAIXA).toList();
        if (caixa.isEmpty() || caixa.stream().anyMatch(payment -> !PaymentMonth.hasKnownLateCharge(
                month.caixaDueDate(), payment.paidOn(), payment.lateChargeCents()))) {
            return Optional.empty();
        }

        long netCharge = caixa.stream().mapToLong(payment -> payment.paidCents()
                - (payment.lateChargeCents() == null ? 0 : payment.lateChargeCents())).sum()
                - month.insuranceCents() - month.feesCents();
        BigDecimal base = BigDecimal.valueOf(month.releasedCents());
        BigDecimal interestRate = annualRate.divide(ANNUAL_PERCENT_TO_MONTHLY, PRECISION);
        BigDecimal nominalInterest = base.multiply(interestRate);
        long roundedNominalInterest = nominalInterest.setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (netCharge < roundedNominalInterest) {
            return Optional.empty();
        }
        if (netCharge == roundedNominalInterest) {
            return Optional.of(BigDecimal.ZERO);
        }

        // Invert correction + interest on the corrected balance, before cent rounding.
        BigDecimal estimated = BigDecimal.valueOf(netCharge).subtract(nominalInterest)
                .divide(base.multiply(BigDecimal.ONE.add(interestRate)), PRECISION).movePointRight(2);
        return estimated.compareTo(MAX_PERCENT) <= 0
                ? Optional.of(estimated.setScale(6, RoundingMode.HALF_UP)) : Optional.empty();
    }
}
