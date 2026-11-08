package br.com.minhaobra.dto;

import br.com.minhaobra.domain.ProjectionCalculator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;

public final class Projection {
    private static final long MAX_CENTS = 10_000_000_000L;

    private Projection() {
    }

    public record Input(
            @Pattern(regexp = "(?:0|[1-9][0-9]{0,2})(?:\\.[0-9]{1,6})?") @DecimalMax("100") String monthlyTr,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal insuranceCents,
            @Digits(integer = 11, fraction = 0) @Min(0) @Max(MAX_CENTS) BigDecimal feesCents) {
    }

    public record Row(String month, long releasedCents, long releaseCents, boolean settled,
            Long trCents, Long interestCents, Long insuranceCents, Long feesCents, long knownSubtotalCents,
            Long totalCents, Long caixaPaidCents, boolean paymentAllocationKnown) {
        private static Row from(ProjectionCalculator.Row row) {
            return new Row(row.month().toString(), row.releasedCents(), row.releaseCents(), row.settled(),
                    row.trCents(), row.interestCents(), row.insuranceCents(), row.feesCents(), row.knownSubtotalCents(),
                    row.totalCents(), row.caixaPaidCents(), row.paymentAllocationKnown());
        }
    }

    public record Result(List<Row> rows, long knownSubtotalCents, Long totalCents) {
        public static Result from(ProjectionCalculator.Result result) {
            return new Result(result.rows().stream().map(Row::from).toList(), result.knownSubtotalCents(),
                    result.totalCents());
        }
    }
}
