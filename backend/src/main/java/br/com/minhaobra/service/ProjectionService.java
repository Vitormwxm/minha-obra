package br.com.minhaobra.service;

import br.com.minhaobra.domain.PaymentMonth;
import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Status;
import br.com.minhaobra.domain.ProjectionCalculator;
import br.com.minhaobra.domain.ProjectionCalculator.RecordedCharge;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.Finance;
import br.com.minhaobra.dto.Projection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProjectionService {
    private static final ZoneId FINANCE_ZONE = ZoneId.of("America/Fortaleza");
    private final FinanceService finance;
    private final Clock clock;

    public ProjectionService(FinanceService finance, Clock clock) {
        this.finance = finance;
        this.clock = clock;
    }

    public Projection.Result project(SessionPrincipal principal, Projection.Input input) {
        Finance.Ledger ledger = finance.ledger(principal);
        Finance.FinancingView financing = ledger.financing();
        if (financing == null) {
            throw new ApiException(404, "FINANCING_NOT_FOUND", "Cadastre o financiamento antes de simular a evolução.");
        }

        YearMonth start = input.startMonth() == null
                ? YearMonth.now(clock.withZone(FINANCE_ZONE)) : YearMonth.parse(input.startMonth());
        YearMonth delivery = YearMonth.from(LocalDate.parse(financing.expectedDeliveryDate()));
        long months = ChronoUnit.MONTHS.between(start, delivery) + 1;
        if (input.startMonth() != null && (months < 1 || months > 120)) {
            throw new ApiException(400, "INVALID_PROJECTION_PERIOD", "Informe um período de 1 a 120 meses.");
        }
        long openingBalance = input.releasedCents() == null ? financing.releasedCents() : input.releasedCents().longValueExact();
        if (openingBalance > financing.financedCents()) {
            throw new ApiException(400, "RELEASE_EXCEEDS_FINANCING",
                    "O saldo liberado precisa ser menor ou igual ao valor financiado.");
        }
        var scenario = new ProjectionCalculator.Scenario(start, delivery, financing.financedCents(),
                openingBalance, new BigDecimal(financing.annualNominalRate()),
                input.monthlyTr() == null ? null : new BigDecimal(input.monthlyTr()),
                optionalCents(input.insuranceCents()), optionalCents(input.feesCents()));
        Map<YearMonth, RecordedCharge> recorded = new HashMap<>();
        for (Finance.MonthView month : ledger.months()) {
            YearMonth competence = YearMonth.parse(month.month());
            if (!competence.isBefore(start) && !competence.isAfter(delivery)) {
                recorded.put(competence, recordedCharge(month));
            }
        }
        return Projection.Result.from(ProjectionCalculator.calculate(scenario, recorded));
    }

    private static RecordedCharge recordedCharge(Finance.MonthView month) {
        boolean settled = month.caixaStatus() != Status.OPEN;
        LocalDate dueDate = month.caixaDueDate() == null ? null : LocalDate.parse(month.caixaDueDate());
        long maximumPaidTowardEstimate = 0;
        boolean allocationKnown = true;
        for (Finance.PaymentView payment : month.payments()) {
            if (payment.kind() == Kind.CAIXA) {
                allocationKnown &= PaymentMonth.hasKnownLateCharge(dueDate, LocalDate.parse(payment.paidOn()),
                        payment.lateChargeCents());
                maximumPaidTowardEstimate += payment.paidCents() - (payment.lateChargeCents() == null
                        ? 0 : payment.lateChargeCents());
            }
        }
        return new RecordedCharge(settled, month.caixaPaidCents(), maximumPaidTowardEstimate, allocationKnown);
    }

    private static Long optionalCents(BigDecimal value) {
        return value == null ? null : value.longValueExact();
    }
}
