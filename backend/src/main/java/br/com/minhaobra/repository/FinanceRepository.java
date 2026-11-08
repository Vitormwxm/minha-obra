package br.com.minhaobra.repository;

import br.com.minhaobra.domain.Financing;
import br.com.minhaobra.domain.PaymentMonth;
import br.com.minhaobra.domain.PaymentMonth.Kind;
import br.com.minhaobra.domain.PaymentMonth.Payment;
import br.com.minhaobra.domain.PaymentMonth.Status;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceRepository {
    private static final String SELECT_MONTH = """
            SELECT m.*, tr.rate_percent AS official_tr
            FROM finance_month m
            LEFT JOIN official_tr_rate tr
              ON tr.period_start = m.tr_period_start AND tr.period_end = m.tr_period_end
            WHERE m.financing_id = ?
            """;

    private final JdbcTemplate jdbc;

    public FinanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Financing> financing(UUID owner) {
        return jdbc.query("""
                SELECT id, version, financed_cents, released_cents, annual_nominal_rate,
                       expected_delivery_date, tracking_start_month, subsidy_cents
                FROM financing WHERE owner_id = ?
                """, (r, n) -> new Financing(r.getObject("id", UUID.class), r.getLong("version"),
                        r.getLong("financed_cents"), r.getLong("released_cents"),
                        r.getBigDecimal("annual_nominal_rate"), r.getObject("expected_delivery_date", LocalDate.class),
                        YearMonth.from(r.getObject("tracking_start_month", LocalDate.class)),
                        r.getObject("subsidy_cents", Long.class)), owner).stream().findFirst();
    }

    public long saveFinancing(UUID owner, Financing financing) {
        return jdbc.queryForObject("""
                INSERT INTO financing (id, owner_id, version, financed_cents, released_cents,
                    annual_nominal_rate, expected_delivery_date, tracking_start_month, subsidy_cents)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version,
                    financed_cents = EXCLUDED.financed_cents, released_cents = EXCLUDED.released_cents,
                    annual_nominal_rate = EXCLUDED.annual_nominal_rate,
                    expected_delivery_date = EXCLUDED.expected_delivery_date,
                    tracking_start_month = EXCLUDED.tracking_start_month, subsidy_cents = EXCLUDED.subsidy_cents
                WHERE financing.owner_id = EXCLUDED.owner_id
                RETURNING version
                """, Long.class, financing.id(), owner, financing.version(), financing.financedCents(),
                financing.releasedCents(), financing.annualNominalRate(), financing.expectedDeliveryDate(),
                financing.trackingStartMonth().atDay(1), financing.subsidyCents());
    }

    public List<PaymentMonth> months(UUID financing) {
        return jdbc.query(SELECT_MONTH + " ORDER BY m.competence", this::mapMonth, financing);
    }

    public Optional<PaymentMonth> month(UUID financing, YearMonth month) {
        return jdbc.query(SELECT_MONTH + " AND m.competence = ?", this::mapMonth,
                financing, month.atDay(1)).stream().findFirst();
    }

    public Optional<BigDecimal> officialTr(LocalDate start, LocalDate end) {
        return jdbc.query("SELECT rate_percent FROM official_tr_rate WHERE period_start = ? AND period_end = ?",
                (r, n) -> r.getBigDecimal("rate_percent"), start, end).stream().findFirst();
    }

    public void cacheOfficialTr(LocalDate start, LocalDate end, BigDecimal rate) {
        jdbc.update("""
                INSERT INTO official_tr_rate (period_start, period_end, rate_percent) VALUES (?, ?, ?)
                ON CONFLICT (period_start, period_end) DO UPDATE SET rate_percent = EXCLUDED.rate_percent
                """, start, end, rate);
    }

    public Optional<Long> monthVersion(UUID financing, YearMonth month) {
        return jdbc.query("SELECT version FROM finance_month WHERE financing_id = ? AND competence = ?",
                (r, n) -> r.getLong("version"), financing, month.atDay(1)).stream().findFirst();
    }

    public boolean hasMonthsBefore(UUID financing, YearMonth month) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT FROM finance_month WHERE financing_id = ? AND competence < ?)
                """, Boolean.class, financing, month.atDay(1));
    }

    public boolean hasMonthsReleasedAbove(UUID financing, long financedCents) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT FROM finance_month WHERE financing_id = ? AND released_cents > ?)
                """, Boolean.class, financing, financedCents);
    }

    public Map<YearMonth, List<Payment>> payments(UUID financing) {
        return jdbc.query("""
                SELECT competence, kind, paid_on, paid_cents, late_charge_cents
                FROM finance_payment WHERE financing_id = ? ORDER BY competence, position
                """, r -> {
                    Map<YearMonth, List<Payment>> payments = new LinkedHashMap<>();
                    while (r.next()) {
                        YearMonth month = YearMonth.from(r.getObject("competence", LocalDate.class));
                        payments.computeIfAbsent(month, ignored -> new ArrayList<>()).add(
                                new Payment(Kind.valueOf(r.getString("kind")), r.getObject("paid_on", LocalDate.class),
                                        r.getLong("paid_cents"), r.getObject("late_charge_cents", Long.class)));
                    }
                    return payments;
                }, financing);
    }

    public long saveMonth(UUID financing, PaymentMonth month, List<Payment> payments) {
        LocalDate competence = month.month().atDay(1);
        long version = jdbc.queryForObject("""
                INSERT INTO finance_month (financing_id, competence, caixa_due_date, builder_due_date,
                    caixa_status, builder_status, released_cents, insurance_cents, fees_cents,
                    tr_period_start, tr_period_end, manual_tr_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (financing_id, competence) DO UPDATE SET version = EXCLUDED.version,
                    caixa_due_date = EXCLUDED.caixa_due_date, builder_due_date = EXCLUDED.builder_due_date,
                    caixa_status = EXCLUDED.caixa_status, builder_status = EXCLUDED.builder_status,
                    released_cents = EXCLUDED.released_cents, insurance_cents = EXCLUDED.insurance_cents,
                    fees_cents = EXCLUDED.fees_cents, tr_period_start = EXCLUDED.tr_period_start,
                    tr_period_end = EXCLUDED.tr_period_end, manual_tr_percent = EXCLUDED.manual_tr_percent
                RETURNING version
                """, Long.class, financing, competence, month.caixaDueDate(), month.builderDueDate(),
                month.caixaStatus().name(), month.builderStatus().name(), month.releasedCents(),
                month.insuranceCents(), month.feesCents(), month.trPeriodStart(), month.trPeriodEnd(), month.manualTr());
        jdbc.update("DELETE FROM finance_payment WHERE financing_id = ? AND competence = ?", financing, competence);

        List<Object[]> values = new ArrayList<>(payments.size());
        for (int position = 0; position < payments.size(); position++) {
            Payment payment = payments.get(position);
            values.add(new Object[]{financing, competence, position, payment.kind().name(), payment.paidOn(),
                    payment.paidCents(), payment.lateChargeCents()});
        }
        jdbc.batchUpdate("""
                INSERT INTO finance_payment (financing_id, competence, position, kind, paid_on,
                    paid_cents, late_charge_cents) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, values);
        return version;
    }

    public void deleteMonth(UUID financing, YearMonth month) {
        jdbc.update("DELETE FROM finance_month WHERE financing_id = ? AND competence = ?", financing, month.atDay(1));
    }

    private PaymentMonth mapMonth(ResultSet row, int position) throws SQLException {
        return new PaymentMonth(YearMonth.from(row.getObject("competence", LocalDate.class)), row.getLong("version"),
                row.getObject("caixa_due_date", LocalDate.class), row.getObject("builder_due_date", LocalDate.class),
                Status.valueOf(row.getString("caixa_status")), Status.valueOf(row.getString("builder_status")),
                row.getObject("released_cents", Long.class), row.getObject("insurance_cents", Long.class),
                row.getObject("fees_cents", Long.class),
                row.getObject("tr_period_start", LocalDate.class), row.getObject("tr_period_end", LocalDate.class),
                row.getBigDecimal("manual_tr_percent"), row.getBigDecimal("official_tr"));
    }
}
