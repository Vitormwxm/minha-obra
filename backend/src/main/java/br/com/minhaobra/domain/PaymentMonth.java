package br.com.minhaobra.domain;

import java.time.LocalDate;
import java.time.YearMonth;

public record PaymentMonth(YearMonth month, long version, LocalDate caixaDueDate, LocalDate builderDueDate,
        Status caixaStatus, Status builderStatus, Long releasedCents, Long insuranceCents, Long feesCents) {

    public enum Status {
        OPEN, PAID, NO_PAYMENT
    }

    public enum Kind {
        CAIXA, CONSTRUTORA
    }

    public record Payment(Kind kind, LocalDate paidOn, long paidCents, Long lateChargeCents) {
    }
}
