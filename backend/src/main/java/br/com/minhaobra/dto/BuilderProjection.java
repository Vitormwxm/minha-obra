package br.com.minhaobra.dto;

import br.com.minhaobra.domain.BuilderProjectionCalculator;
import br.com.minhaobra.domain.BuilderProjectionCalculator.IndexStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class BuilderProjection {
    private static final long MAX_CENTS = 10_000_000_000L;
    private static final String MONTH = "(?:19[0-9]{2}|20[0-9]{2}|2100)-(?:0[1-9]|1[0-2])";

    private BuilderProjection() {
    }

    public record Input(
            @NotNull @Size(min = 1, max = BuilderProjectionCalculator.MAX_INSTALLMENTS)
            List<@NotNull @Valid Installment> installments,
            @Valid Incc incc) {
    }

    public record Installment(
            @NotNull @Pattern(regexp = MONTH) String month,
            @NotNull @Digits(integer = 11, fraction = 0) @Min(1) @Max(MAX_CENTS) BigDecimal baseCents,
            @NotNull Boolean indexed) {
    }

    public record Incc(
            @NotNull @Pattern(regexp = "M|DI|10") String variant,
            @NotNull @Pattern(regexp = MONTH) String baseMonth,
            @NotNull @Digits(integer = 3, fraction = 0) @Min(1) @Max(120) BigDecimal intervalMonths,
            @NotNull @Digits(integer = 2, fraction = 0) @Min(0) @Max(12) BigDecimal lagMonths,
            @Pattern(regexp = "-?(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,6})?")
            @DecimalMin(value = "-100", inclusive = false) @DecimalMax("100") String monthlyRate) {
    }

    public record Row(String month, long baseCents, Long projectedCents, Long remainingCents,
            Long paidCents, boolean settled, IndexStatus indexStatus, long knownSubtotalCents,
            boolean paymentAllocationKnown) {
        private static Row from(BuilderProjectionCalculator.Row row) {
            return new Row(row.month().toString(), row.baseCents(), row.projectedCents(), row.remainingCents(),
                    row.paidCents(), row.settled(), row.indexStatus(), row.knownSubtotalCents(),
                    row.paymentAllocationKnown());
        }
    }

    public record Result(List<Row> rows, Long remainingCents, long knownSubtotalCents) {
        public static Result from(BuilderProjectionCalculator.Result result) {
            return new Result(result.rows().stream().map(Row::from).toList(), result.remainingCents(),
                    result.knownSubtotalCents());
        }
    }
}
