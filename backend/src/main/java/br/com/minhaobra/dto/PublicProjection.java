package br.com.minhaobra.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public final class PublicProjection {
    private static final long MAX_CENTS = 10_000_000_000L;
    private static final String PERCENT = "(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,6})?";
    private static final String MONTH = "(?:19[0-9]{2}|20[0-9]{2}|2100)-(?:0[1-9]|1[0-2])";

    private PublicProjection() {
    }

    public record Input(
            @NotNull @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal releasedCents,
            @NotNull @Digits(integer = 11, fraction = 0) @Min(1) @Max(MAX_CENTS) BigDecimal financedCents,
            @NotNull @Pattern(regexp = PERCENT) @DecimalMax("100") String annualNominalRate,
            @Pattern(regexp = PERCENT) @DecimalMax("100") String monthlyTr,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal insuranceCents,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal feesCents,
            @NotNull @Pattern(regexp = MONTH) String startMonth,
            @NotNull @Pattern(regexp = MONTH) String endMonth) {
    }
}
