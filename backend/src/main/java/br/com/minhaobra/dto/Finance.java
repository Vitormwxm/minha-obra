package br.com.minhaobra.dto;

import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class Finance {
    public static final int MAX_PAYMENTS = 100;
    private static final long MAX_CENTS = 10_000_000_000L;
    private static final long MAX_VERSION = 9_007_199_254_740_991L;
    private static final String DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}";
    private static final String MONTH_PATTERN = "\\d{4}-\\d{2}";

    private Finance() {
    }

    public record FinancingInput(
            @Digits(integer = 16, fraction = 0) @Min(0) @Max(MAX_VERSION) BigDecimal version,
            @NotNull @Digits(integer = 11, fraction = 0) @Min(1) @Max(MAX_CENTS) BigDecimal financedCents,
            @NotNull @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal releasedCents,
            @NotNull @Pattern(regexp = "(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,6})?")
            @DecimalMax("100") String annualNominalRate,
            @NotNull @Pattern(regexp = DATE_PATTERN) String expectedDeliveryDate,
            @NotNull @Pattern(regexp = MONTH_PATTERN) String trackingStartMonth,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal subsidyCents) {
    }

    public record MonthInput(
            @Digits(integer = 16, fraction = 0) @Min(0) @Max(MAX_VERSION) BigDecimal version,
            @Pattern(regexp = DATE_PATTERN) String caixaDueDate,
            @Pattern(regexp = DATE_PATTERN) String builderDueDate,
            @NotNull Status caixaStatus,
            @NotNull Status builderStatus,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal releasedCents,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal insuranceCents,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal feesCents,
            @NotNull @Size(max = MAX_PAYMENTS) List<@NotNull @Valid PaymentInput> payments) {
    }

    public record PaymentInput(
            @NotNull Kind kind,
            @NotNull @Pattern(regexp = DATE_PATTERN) String paidOn,
            @NotNull @Digits(integer = 11, fraction = 0) @Min(1) @Max(MAX_CENTS) BigDecimal paidCents,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal lateChargeCents) {
    }

    public record DeleteMonth(@NotNull @Digits(integer = 16, fraction = 0) @Min(0) @Max(MAX_VERSION)
            BigDecimal version) {
    }

    public record Revision(long version) {
    }

    public record FinancingView(UUID id, long version, long financedCents, long releasedCents,
            String annualNominalRate, String expectedDeliveryDate, String trackingStartMonth, Long subsidyCents) {
    }

    public record PaymentView(Kind kind, String paidOn, long paidCents, Long lateChargeCents) {
    }

    public record MonthView(String month, long version, String caixaDueDate, String builderDueDate,
            Status caixaStatus, Status builderStatus, Long releasedCents, Long insuranceCents, Long feesCents,
            List<PaymentView> payments, boolean closed, Long caixaPaidCents, Long builderPaidCents,
            Long monthlyTotalCents, long caixaAccumulatedCents, long builderAccumulatedCents,
            long accumulatedPaidCents, Long monthlyVariationCents) {
    }

    public record Ledger(FinancingView financing, List<MonthView> months, List<String> missingMonths) {
    }
}
