package br.com.minhaobra.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

public record PaymentMonth(YearMonth month, long version, LocalDate caixaDueDate, LocalDate builderDueDate,
        Status caixaStatus, Status builderStatus, Long releasedCents, Long insuranceCents, Long feesCents,
        LocalDate trPeriodStart, LocalDate trPeriodEnd, BigDecimal manualTr, BigDecimal officialTr) {

    public static boolean hasKnownLateCharge(LocalDate dueDate, LocalDate paidOn, Long lateChargeCents) {
        return lateChargeCents != null || (dueDate != null && !paidOn.isAfter(dueDate));
    }

    public enum Status {
        OPEN, PAID, NO_PAYMENT
    }

    public enum Kind {
        CAIXA, CONSTRUTORA
    }

    public record Payment(Kind kind, LocalDate paidOn, long paidCents, Long lateChargeCents) {
    }
}
