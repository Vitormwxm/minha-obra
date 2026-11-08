package br.com.minhaobra.service;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.domain.Financing;
import br.com.minhaobra.domain.PaymentMonth;
import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Payment;
import br.com.minhaobra.domain.PaymentMonth.Status;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.Finance;
import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.repository.FinanceRepository;
import br.com.minhaobra.security.SecretCrypto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class FinanceService {
    private static final ZoneId FINANCE_ZONE = ZoneId.of("America/Fortaleza");
    private final AccountService accounts;
    private final AccountRepository requests;
    private final FinanceRepository finance;
    private final SecretCrypto crypto;
    private final ObjectMapper json;
    private final Clock clock;

    public FinanceService(AccountService accounts, AccountRepository requests, FinanceRepository finance,
            SecretCrypto crypto, ObjectMapper json, Clock clock) {
        this.accounts = accounts;
        this.requests = requests;
        this.finance = finance;
        this.crypto = crypto;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Finance.Ledger ledger(SessionPrincipal principal) {
        Account account = accounts.sessionAccount(principal)
                .orElseThrow(() -> new ApiException(401, "SESSION_EXPIRED", "Sua sessão expirou. Entre novamente."));
        requireAuthenticated(account, principal);
        return finance.financing(account.id()).map(this::buildLedger)
                .orElseGet(() -> new Finance.Ledger(null, List.of(), List.of()));
    }

    @Transactional
    public Finance.Revision saveFinancing(SessionPrincipal principal, UUID key, Finance.FinancingInput input) {
        UUID owner = lockOwner(principal);
        return idempotent(owner, "finance:financing", key, input, () -> {
            Financing previous = finance.financing(owner).orElse(null);
            requireVersion(previous == null ? null : previous.version(), input.version());
            long financedCents = input.financedCents().longValueExact();
            long releasedCents = input.releasedCents().longValueExact();
            LocalDate deliveryDate = parseDate(input.expectedDeliveryDate());
            YearMonth trackingStart = parseMonth(input.trackingStartMonth());
            if (deliveryDate.isBefore(trackingStart.atDay(1))) {
                throw invalid("INVALID_DELIVERY_DATE", "A entrega deve ocorrer a partir do mês de início.");
            }
            checkReleased(releasedCents, financedCents);
            if (previous != null && finance.hasMonthsBefore(previous.id(), trackingStart)) {
                throw invalid("TRACKING_START_AFTER_ENTRY", "O mês de início deve incluir os lançamentos existentes.");
            }
            if (previous != null && finance.hasMonthsReleasedAbove(previous.id(), financedCents)) {
                throw invalid("RELEASED_EXCEEDS_FINANCED", "Já existe um saldo mensal acima do valor financiado.");
            }
            Financing financing = new Financing(previous == null ? UUID.randomUUID() : previous.id(),
                    previous == null ? 0 : previous.version() + 1, financedCents, releasedCents,
                    new BigDecimal(input.annualNominalRate()), deliveryDate, trackingStart,
                    optionalLong(input.subsidyCents()));
            return new Finance.Revision(finance.saveFinancing(owner, financing));
        });
    }

    @Transactional
    public Finance.Revision saveMonth(SessionPrincipal principal, String monthValue, UUID key, Finance.MonthInput input) {
        UUID owner = lockOwner(principal);
        YearMonth month = parseMonth(monthValue);
        return idempotent(owner, "finance:month:" + month, key, input, () -> {
            Financing financing = requireFinancing(owner);
            Long currentVersion = finance.monthVersion(financing.id(), month).orElse(null);
            requireVersion(currentVersion, input.version());
            if (month.isBefore(financing.trackingStartMonth())) {
                throw invalid("MONTH_BEFORE_TRACKING_START", "O lançamento deve ocorrer a partir do mês de início.");
            }
            Long released = optionalLong(input.releasedCents());
            checkReleased(released, financing.financedCents());
            LocalDate caixaDue = dueDate(input.caixaDueDate(), month);
            LocalDate builderDue = dueDate(input.builderDueDate(), month);
            LocalDate today = LocalDate.now(clock.withZone(FINANCE_ZONE));
            List<Payment> payments = input.payments().stream().map(payment -> payment(payment, today)).toList();
            checkStatus(input.caixaStatus(), payments, Kind.CAIXA);
            checkStatus(input.builderStatus(), payments, Kind.CONSTRUTORA);
            PaymentMonth record = new PaymentMonth(month, currentVersion == null ? 0 : currentVersion,
                    caixaDue, builderDue, input.caixaStatus(), input.builderStatus(), released,
                    optionalLong(input.insuranceCents()), optionalLong(input.feesCents()));
            return new Finance.Revision(finance.saveMonth(financing.id(), record, payments));
        });
    }

    @Transactional
    public void deleteMonth(SessionPrincipal principal, String monthValue, UUID key, Finance.DeleteMonth input) {
        UUID owner = lockOwner(principal);
        YearMonth month = parseMonth(monthValue);
        idempotent(owner, "finance:delete:" + month, key, input, () -> {
            Financing financing = requireFinancing(owner);
            long version = finance.monthVersion(financing.id(), month)
                    .orElseThrow(() -> new ApiException(404, "MONTH_NOT_FOUND", "Lançamento mensal não encontrado."));
            requireVersion(version, input.version());
            finance.deleteMonth(financing.id(), month);
            return new Finance.Revision(version);
        });
    }

    private Finance.Ledger buildLedger(Financing financing) {
        List<PaymentMonth> months = finance.months(financing.id());
        Map<YearMonth, List<Payment>> paymentsByMonth = finance.payments(financing.id());
        List<Finance.MonthView> views = new ArrayList<>(months.size());
        List<String> missing = new ArrayList<>();
        YearMonth current = YearMonth.now(clock.withZone(FINANCE_ZONE));
        YearMonth expected = financing.trackingStartMonth();
        Finance.MonthView previous = null;
        long caixaAccumulated = 0;
        long builderAccumulated = 0;
        for (PaymentMonth month : months) {
            YearMonth competence = month.month();
            boolean consecutive = expected.equals(competence);
            while (expected.isBefore(competence)) {
                missing.add(expected.toString());
                expected = expected.plusMonths(1);
            }
            expected = competence.plusMonths(1);

            List<Payment> payments = paymentsByMonth.getOrDefault(competence, List.of());
            long caixaRecorded = total(payments, Kind.CAIXA);
            long builderRecorded = total(payments, Kind.CONSTRUTORA);
            Long caixaPaid = knownAmount(caixaRecorded, month.caixaStatus());
            Long builderPaid = knownAmount(builderRecorded, month.builderStatus());
            Long total = caixaPaid == null || builderPaid == null ? null : caixaPaid + builderPaid;
            boolean closed = competence.isBefore(current)
                    && month.caixaStatus() != Status.OPEN && month.builderStatus() != Status.OPEN;
            Long variation = closed && consecutive && previous != null && previous.closed()
                    ? total - previous.monthlyTotalCents() : null;

            caixaAccumulated += caixaRecorded;
            builderAccumulated += builderRecorded;
            var view = new Finance.MonthView(competence.toString(), month.version(),
                    dateString(month.caixaDueDate()), dateString(month.builderDueDate()),
                    month.caixaStatus(), month.builderStatus(), month.releasedCents(), month.insuranceCents(),
                    month.feesCents(), payments.stream().map(FinanceService::paymentView).toList(), closed,
                    caixaPaid, builderPaid, total, caixaAccumulated, builderAccumulated,
                    caixaAccumulated + builderAccumulated, variation);
            views.add(view);
            previous = view;
        }
        var details = new Finance.FinancingView(financing.id(), financing.version(), financing.financedCents(),
                financing.releasedCents(), financing.annualNominalRate().stripTrailingZeros().toPlainString(),
                dateString(financing.expectedDeliveryDate()), financing.trackingStartMonth().toString(),
                financing.subsidyCents());
        return new Finance.Ledger(details, views, missing);
    }

    private UUID lockOwner(SessionPrincipal principal) {
        Account owner = accounts.currentForUpdate(principal);
        requireAuthenticated(owner, principal);
        return owner.id();
    }

    private static void requireAuthenticated(Account account, SessionPrincipal principal) {
        if (!"AUTHENTICATED".equals(AccountService.stage(account, principal.mfaVerified()))) {
            throw ApiException.forbidden();
        }
    }

    private Financing requireFinancing(UUID owner) {
        return finance.financing(owner).orElseThrow(() -> new ApiException(
                404, "FINANCING_NOT_FOUND", "Cadastre o financiamento antes de registrar pagamentos."));
    }

    private Finance.Revision idempotent(UUID owner, String operation, UUID key, Object input,
            Supplier<Finance.Revision> change) {
        String fingerprint = crypto.fingerprint(operation, json.writeValueAsString(input));
        requests.requestLock(owner + ":" + operation + ":" + key);
        var cached = requests.cached(owner, operation, key);
        if (cached.isPresent()) {
            var result = cached.get();
            if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                    result.fingerprint().getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Esta chave já foi usada em outra solicitação.");
            }
            return json.readValue(result.response(), Finance.Revision.class);
        }
        Finance.Revision revision = change.get();
        requests.saveCached(owner, operation, key, fingerprint, json.writeValueAsString(revision));
        return revision;
    }

    private static void requireVersion(Long current, BigDecimal requested) {
        if (!Objects.equals(current, optionalLong(requested))) {
            throw ApiException.conflict();
        }
    }

    private static YearMonth parseMonth(String value) {
        try {
            YearMonth month = YearMonth.parse(value);
            if (value.length() == 7 && validYear(month.getYear())) {
                return month;
            }
        } catch (DateTimeParseException ignored) {
            // Return the same validation error for malformed values and unsupported years.
        }
        throw invalid("INVALID_MONTH", "Informe um mês válido entre 1900 e 2100.");
    }

    private static LocalDate parseDate(String value) {
        try {
            LocalDate date = LocalDate.parse(value);
            if (validYear(date.getYear())) {
                return date;
            }
        } catch (DateTimeParseException ignored) {
            // Do not expose submitted data in the error.
        }
        throw invalid("INVALID_DATE", "Informe uma data válida entre 1900 e 2100.");
    }

    private static boolean validYear(int year) {
        return year >= 1900 && year <= 2100;
    }

    private static LocalDate dueDate(String value, YearMonth month) {
        if (value == null) {
            return null;
        }
        LocalDate date = parseDate(value);
        if (!YearMonth.from(date).equals(month)) {
            throw invalid("DUE_DATE_OUTSIDE_MONTH", "O vencimento deve estar dentro do mês do lançamento.");
        }
        return date;
    }

    private static Payment payment(Finance.PaymentInput input, LocalDate today) {
        LocalDate paidOn = parseDate(input.paidOn());
        if (paidOn.isAfter(today)) {
            throw invalid("FUTURE_PAYMENT", "A data do pagamento deve ser hoje ou anterior.");
        }
        long paid = input.paidCents().longValueExact();
        Long late = optionalLong(input.lateChargeCents());
        if (late != null && late > paid) {
            throw invalid("LATE_CHARGE_EXCEEDS_PAYMENT", "Os encargos por atraso devem estar incluídos no valor pago.");
        }
        return new Payment(input.kind(), paidOn, paid, late);
    }

    private static void checkStatus(Status status, List<Payment> payments, Kind kind) {
        boolean hasPayment = payments.stream().anyMatch(payment -> payment.kind() == kind);
        if ((status == Status.PAID && !hasPayment) || (status == Status.NO_PAYMENT && hasPayment)) {
            throw invalid("PAYMENT_STATUS_MISMATCH", "O status de " + kind + " precisa corresponder aos pagamentos.");
        }
    }

    private static void checkReleased(Long released, long financed) {
        if (released != null && released > financed) {
            throw invalid("RELEASED_EXCEEDS_FINANCED", "O saldo liberado deve caber no valor financiado.");
        }
    }

    private static long total(List<Payment> payments, Kind kind) {
        return payments.stream().filter(payment -> payment.kind() == kind).mapToLong(Payment::paidCents).sum();
    }

    private static Long knownAmount(long recorded, Status status) {
        return recorded == 0 && status == Status.OPEN ? null : recorded;
    }

    private static Long optionalLong(BigDecimal value) {
        return value == null ? null : value.longValueExact();
    }

    private static String dateString(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static Finance.PaymentView paymentView(Payment payment) {
        return new Finance.PaymentView(payment.kind(), dateString(payment.paidOn()), payment.paidCents(),
                payment.lateChargeCents());
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(400, code, message);
    }
}
