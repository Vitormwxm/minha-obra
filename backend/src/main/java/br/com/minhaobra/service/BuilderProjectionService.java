package br.com.minhaobra.service;

import br.com.minhaobra.domain.BuilderProjectionCalculator;
import br.com.minhaobra.domain.BuilderProjectionCalculator.Incc;
import br.com.minhaobra.domain.BuilderProjectionCalculator.Installment;
import br.com.minhaobra.domain.PaymentMonth;
import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Status;
import br.com.minhaobra.domain.ProjectionCalculator.RecordedCharge;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.BuilderProjection;
import br.com.minhaobra.dto.Finance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class BuilderProjectionService {
    private static final ZoneId FINANCE_ZONE = ZoneId.of("America/Fortaleza");
    private final FinanceService finance;
    private final Clock clock;

    public BuilderProjectionService(FinanceService finance, Clock clock) {
        this.finance = finance;
        this.clock = clock;
    }

    public BuilderProjection.Result project(SessionPrincipal principal, BuilderProjection.Input input) {
        Finance.Ledger ledger = finance.ledger(principal);
        if (ledger.financing() == null) {
            throw new ApiException(404, "FINANCING_NOT_FOUND", "Cadastre o financiamento antes de simular as parcelas.");
        }

        YearMonth current = YearMonth.now(clock.withZone(FINANCE_ZONE));
        YearMonth delivery = YearMonth.from(LocalDate.parse(ledger.financing().expectedDeliveryDate()));
        Incc incc = toIncc(input.incc());
        var installments = installments(input, current, delivery, incc);

        Map<YearMonth, RecordedCharge> recorded = new HashMap<>();
        for (Finance.MonthView month : ledger.months()) {
            YearMonth competence = YearMonth.parse(month.month());
            if (installments.containsKey(competence)) {
                recorded.put(competence, recordedCharge(month));
            }
        }

        try {
            return BuilderProjection.Result.from(
                    BuilderProjectionCalculator.calculate(List.copyOf(installments.values()), incc, recorded));
        } catch (ArithmeticException outOfRange) {
            throw new ApiException(400, "BUILDER_PROJECTION_AMOUNT",
                    "O valor projetado ultrapassou o limite. Revise o INCC e os valores das parcelas.");
        }
    }

    private static Incc toIncc(BuilderProjection.Incc input) {
        if (input == null) {
            return null;
        }
        BigDecimal rate = input.monthlyRate() == null ? null : new BigDecimal(input.monthlyRate());
        return new Incc(YearMonth.parse(input.baseMonth()), input.intervalMonths().intValueExact(),
                input.lagMonths().intValueExact(), rate);
    }

    private static Map<YearMonth, Installment> installments(BuilderProjection.Input input, YearMonth current,
            YearMonth delivery, Incc incc) {
        Map<YearMonth, Installment> installments = new TreeMap<>();
        for (BuilderProjection.Installment item : input.installments()) {
            YearMonth month = YearMonth.parse(item.month());
            if (month.isBefore(current) || month.isAfter(delivery)) {
                throw new ApiException(400, "BUILDER_PROJECTION_MONTH",
                        "Escolha meses entre o mês atual e o mês previsto para a entrega.");
            }
            if (item.indexed() && incc != null && month.isBefore(incc.baseMonth())) {
                throw new ApiException(400, "BUILDER_PROJECTION_BASE_MONTH",
                        "O mês-base do INCC precisa ser igual ou anterior aos meses das parcelas corrigidas.");
            }
            if (installments.putIfAbsent(month, new Installment(month, item.baseCents().longValueExact(),
                    item.indexed())) != null) {
                throw new ApiException(400, "BUILDER_PROJECTION_DUPLICATE_MONTH",
                        "Informe apenas uma parcela por mês. Some as cobranças da mesma competência.");
            }
        }
        return installments;
    }

    private static RecordedCharge recordedCharge(Finance.MonthView month) {
        LocalDate dueDate = month.builderDueDate() == null ? null : LocalDate.parse(month.builderDueDate());
        long maximumPaidTowardEstimate = 0;
        boolean allocationKnown = true;
        for (Finance.PaymentView payment : month.payments()) {
            if (payment.kind() == Kind.CONSTRUTORA) {
                allocationKnown &= PaymentMonth.hasKnownLateCharge(dueDate, LocalDate.parse(payment.paidOn()),
                        payment.lateChargeCents());
                maximumPaidTowardEstimate += payment.paidCents() - (payment.lateChargeCents() == null
                        ? 0 : payment.lateChargeCents());
            }
        }
        return new RecordedCharge(month.builderStatus() != Status.OPEN, month.builderPaidCents(),
                maximumPaidTowardEstimate, allocationKnown);
    }
}
