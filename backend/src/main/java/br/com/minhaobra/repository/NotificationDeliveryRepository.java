package br.com.minhaobra.repository;

import br.com.minhaobra.domain.PaymentMonth;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationDeliveryRepository {
    private static final String ACTIVE_DEVICE = """
            a.status='ACTIVE' AND NOT a.must_change_password AND a.auth_version=d.auth_version
            AND d.refreshed_at >= CURRENT_TIMESTAMP - INTERVAL '90 days'
            AND NOT EXISTS (SELECT 1 FROM account_deletion_request x WHERE x.account_id=a.id)
            """;
    private static final String PAYMENT_TARGETS = """
            SELECT d.installation_id, d.account_id, d.zone_id, d.auth_version, d.registered_at,
                   f.id AS financing_id, f.tracking_start_month, f.expected_delivery_date, p.enabled_since
            FROM notification_device d
            JOIN app_account a ON a.id=d.account_id
            JOIN notification_preference p ON p.account_id=a.id
            JOIN financing f ON f.owner_id=a.id
            WHERE p.enabled AND a.role='USER' AND
            """ + ACTIVE_DEVICE;

    private final JdbcTemplate jdbc;

    public NotificationDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Target> paymentTargets(UUID after, int limit) {
        return jdbc.query(PAYMENT_TARGETS + """
                 AND (?::uuid IS NULL OR d.installation_id > ?::uuid)
                 ORDER BY d.installation_id LIMIT ?
                """, NotificationDeliveryRepository::target, after, after, limit);
    }

    public Optional<Target> paymentTarget(Job job) {
        return jdbc.query(PAYMENT_TARGETS + """
                 AND d.installation_id=? AND a.id=? AND a.auth_version=? AND f.id=?
                """, NotificationDeliveryRepository::target, job.installationId(), job.recipientId(),
                job.authVersion(), job.financingId()).stream().findFirst();
    }

    public List<Month> months(UUID financing, LocalDate earliest, LocalDate latest) {
        return jdbc.query("""
                SELECT competence, caixa_due_date, builder_due_date, caixa_status, builder_status
                FROM finance_month WHERE financing_id=? AND competence BETWEEN ? AND ?
                """, (row, n) -> new Month(row.getObject("competence", LocalDate.class),
                        row.getObject("caixa_due_date", LocalDate.class),
                        row.getObject("builder_due_date", LocalDate.class),
                        PaymentMonth.Status.valueOf(row.getString("caixa_status")),
                        PaymentMonth.Status.valueOf(row.getString("builder_status"))), financing, earliest, latest);
    }

    public void queuePayment(Target target, PaymentMonth.Kind kind, LocalDate dueOn, LocalDate noticeOn,
            Instant scheduled, Instant expires) {
        jdbc.update("""
                INSERT INTO notification_delivery(id,installation_id,recipient_id,auth_version,notice_key,
                    financing_id,payment_kind,due_on,notice_on,scheduled_at,expires_at,available_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (installation_id,recipient_id,notice_key) DO UPDATE
                    SET auth_version=EXCLUDED.auth_version, scheduled_at=EXCLUDED.scheduled_at,
                        expires_at=EXCLUDED.expires_at, available_at=EXCLUDED.available_at,
                        state='PENDING', attempts=0, leased_until=NULL
                    WHERE notification_delivery.scheduled_at <> EXCLUDED.scheduled_at
                        AND (notification_delivery.state='CANCELED'
                            OR (notification_delivery.state='PENDING' AND notification_delivery.leased_until IS NULL))
                """, UUID.randomUUID(), target.installationId(), target.recipientId(), target.authVersion(),
                "payment:" + kind + ":" + dueOn + ":" + noticeOn, target.financingId(), kind.name(), dueOn, noticeOn,
                Timestamp.from(scheduled), Timestamp.from(expires), Timestamp.from(scheduled));
    }

    public void queueRegistration(UUID eventId, UUID applicant, Instant now) {
        jdbc.update("""
                INSERT INTO notification_delivery(id,installation_id,recipient_id,auth_version,notice_key,
                    registration_id,scheduled_at,expires_at,available_at)
                SELECT gen_random_uuid(),d.installation_id,a.id,a.auth_version,'registration:' || e.id,
                    e.account_id,e.occurred_at,e.occurred_at + INTERVAL '24 hours',?
                FROM account_audit e
                JOIN app_account applicant ON applicant.id=e.account_id AND applicant.status='PENDING_APPROVAL'
                JOIN app_account a ON a.role='ADMIN'
                JOIN notification_device d ON d.account_id=a.id AND d.registered_at<=e.occurred_at
                WHERE e.id=? AND e.account_id=? AND e.event_type='ACCOUNT_REQUESTED'
                    AND e.occurred_at BETWEEN CAST(? AS timestamptz) - INTERVAL '24 hours' AND ?
                    AND
                """ + ACTIVE_DEVICE + """
                ON CONFLICT (installation_id,recipient_id,notice_key) DO NOTHING
                """, Timestamp.from(now), eventId, applicant, Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<Job> claim(Instant now, Instant leaseUntil) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbc.query("""
                UPDATE notification_delivery j SET leased_until=?, attempts=attempts+1
                WHERE j.id=(
                    SELECT id FROM notification_delivery
                    WHERE state='PENDING' AND available_at<=? AND expires_at>?
                        AND (leased_until IS NULL OR leased_until<=?)
                    ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING j.*
                """, NotificationDeliveryRepository::job, Timestamp.from(leaseUntil), timestamp, timestamp, timestamp)
                .stream().findFirst();
    }

    public Optional<String> token(Job job) {
        return jdbc.query("""
                SELECT d.token FROM notification_device d
                JOIN app_account a ON a.id=d.account_id
                WHERE d.installation_id=? AND d.account_id=? AND d.auth_version=? AND
                """ + ACTIVE_DEVICE + """
                 AND (a.role='ADMIN' AND d.registered_at<=? AND EXISTS (
                          SELECT 1 FROM app_account applicant WHERE applicant.id=? AND applicant.status='PENDING_APPROVAL'
                      ) OR a.role='USER' AND ?::uuid IS NULL AND EXISTS (
                          SELECT 1 FROM notification_preference p WHERE p.account_id=a.id AND p.enabled AND p.enabled_since<=?
                      ))
                """, (row, n) -> row.getString("token"), job.installationId(), job.recipientId(), job.authVersion(),
                Timestamp.from(job.scheduledAt()), job.registrationId(), job.registrationId(),
                Timestamp.from(job.scheduledAt())).stream().findFirst();
    }

    public void finish(Job job, State state) {
        jdbc.update("""
                UPDATE notification_delivery SET state=?, leased_until=NULL
                WHERE id=? AND state='PENDING' AND attempts=?
                """, state.name(), job.id(), job.attempts());
    }

    public void retry(Job job, Instant after) {
        jdbc.update("""
                UPDATE notification_delivery SET available_at=?, leased_until=NULL
                WHERE id=? AND state='PENDING' AND attempts=?
                """, Timestamp.from(after), job.id(), job.attempts());
    }

    public void removeInvalidToken(Job job, String token) {
        jdbc.update("""
                DELETE FROM notification_device WHERE installation_id=? AND account_id=? AND auth_version=? AND token=?
                """, job.installationId(), job.recipientId(), job.authVersion(), token);
    }

    public void expire(Instant cutoff) {
        jdbc.update("""
                DELETE FROM notification_delivery WHERE id IN (
                    SELECT id FROM notification_delivery WHERE expires_at < ? ORDER BY expires_at LIMIT 1000
                )
                """, Timestamp.from(cutoff));
    }

    private static Target target(ResultSet row, int n) throws SQLException {
        return new Target(row.getObject("installation_id", UUID.class), row.getObject("account_id", UUID.class),
                row.getLong("auth_version"), row.getString("zone_id"), row.getTimestamp("registered_at").toInstant(),
                row.getTimestamp("enabled_since").toInstant(), row.getObject("financing_id", UUID.class),
                row.getObject("tracking_start_month", LocalDate.class),
                row.getObject("expected_delivery_date", LocalDate.class));
    }

    private static Job job(ResultSet row, int n) throws SQLException {
        String kind = row.getString("payment_kind");
        return new Job(row.getObject("id", UUID.class), row.getObject("installation_id", UUID.class),
                row.getObject("recipient_id", UUID.class), row.getLong("auth_version"),
                row.getObject("registration_id", UUID.class), row.getObject("financing_id", UUID.class),
                kind == null ? null : PaymentMonth.Kind.valueOf(kind), row.getObject("due_on", LocalDate.class),
                row.getObject("notice_on", LocalDate.class), row.getTimestamp("scheduled_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(), row.getInt("attempts"));
    }

    public enum State { SENT, CANCELED, FAILED }

    public record Target(UUID installationId, UUID recipientId, long authVersion, String zoneId, Instant registeredAt,
            Instant preferenceEnabledSince, UUID financingId, LocalDate trackingStart, LocalDate expectedDelivery) {
        public Instant enabledSince() {
            return registeredAt.isAfter(preferenceEnabledSince) ? registeredAt : preferenceEnabledSince;
        }
    }

    public record Month(LocalDate competence, LocalDate caixaDue, LocalDate builderDue, PaymentMonth.Status caixaStatus,
            PaymentMonth.Status builderStatus) {
        public LocalDate due(PaymentMonth.Kind kind) {
            return kind == PaymentMonth.Kind.CAIXA ? caixaDue : builderDue;
        }

        public PaymentMonth.Status status(PaymentMonth.Kind kind) {
            return kind == PaymentMonth.Kind.CAIXA ? caixaStatus : builderStatus;
        }
    }

    public record Job(UUID id, UUID installationId, UUID recipientId, long authVersion, UUID registrationId,
            UUID financingId, PaymentMonth.Kind kind, LocalDate dueOn, LocalDate noticeOn, Instant scheduledAt,
            Instant expiresAt, int attempts) {
    }
}
