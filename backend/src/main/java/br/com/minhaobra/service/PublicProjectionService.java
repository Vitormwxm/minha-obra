package br.com.minhaobra.service;

import br.com.minhaobra.domain.ProjectionCalculator;
import br.com.minhaobra.dto.Projection;
import br.com.minhaobra.dto.PublicProjection;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PublicProjectionService {
    public Projection.Result project(PublicProjection.Input input) {
        long released = input.releasedCents().longValueExact();
        long financed = input.financedCents().longValueExact();
        if (released > financed) {
            throw new ApiException(400, "RELEASE_EXCEEDS_FINANCING",
                    "O saldo liberado precisa ser menor ou igual ao valor financiado.");
        }

        YearMonth start = YearMonth.parse(input.startMonth());
        YearMonth end = YearMonth.parse(input.endMonth());
        long months = ChronoUnit.MONTHS.between(start, end) + 1;
        if (months < 1 || months > 120) {
            throw new ApiException(400, "INVALID_PROJECTION_PERIOD", "Informe um período de 1 a 120 meses.");
        }

        var scenario = new ProjectionCalculator.Scenario(start, end, financed, released,
                new BigDecimal(input.annualNominalRate()),
                input.monthlyTr() == null ? null : new BigDecimal(input.monthlyTr()),
                optionalCents(input.insuranceCents()), optionalCents(input.feesCents()));
        return Projection.Result.from(ProjectionCalculator.calculate(scenario, Map.of()));
    }

    private static Long optionalCents(BigDecimal value) {
        return value == null ? null : value.longValueExact();
    }
}
