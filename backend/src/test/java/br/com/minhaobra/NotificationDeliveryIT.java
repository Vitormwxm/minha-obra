package br.com.minhaobra;

import br.com.minhaobra.repository.NotificationDeliveryRepository;
import br.com.minhaobra.repository.NotificationPreferenceRepository;
import br.com.minhaobra.service.FirebasePushClient;
import br.com.minhaobra.service.NotificationPushScheduler;
import br.com.minhaobra.service.NotificationRegistrationListener;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class NotificationDeliveryIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired NotificationPreferenceRepository preferences;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = required("TEST_DATABASE_URL");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("The tests require a PostgreSQL instance.");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> required("TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> required("TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("app.auth.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.add("app.events.enabled", () -> "false");
        properties.add("app.push.enabled", () -> "false");
    }

    @AfterAll
    static void removeTestSchema() throws Exception {
        if (System.getenv("TEST_DATABASE_URL") == null) {
            return;
        }
        try (var connection = DriverManager.getConnection(required("TEST_DATABASE_URL"),
                required("TEST_DATABASE_USERNAME"), required("TEST_DATABASE_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @Test
    void replayedKafkaEventQueuesOnlyAnAlreadyRegisteredActiveAdminAndRetriesTheSameDelivery() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        UUID applicant = account("USER", "PENDING_APPROVAL");
        UUID administrator = account("ADMIN", "ACTIVE");
        UUID lateAdministrator = account("ADMIN", "ACTIVE");
        UUID otherOwner = account("USER", "ACTIVE");
        String token = device(administrator, now.minusSeconds(600), "UTC");
        device(lateAdministrator, now, "UTC");
        device(otherOwner, now.minusSeconds(600), "UTC");

        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_audit(id,actor_id,account_id,event_type,occurred_at)
                VALUES (?,?,?,'ACCOUNT_REQUESTED',?)
                """, event, applicant, applicant, Timestamp.from(now.minusSeconds(120)));
        var listener = new NotificationRegistrationListener(deliveries, json, clock(now));
        String payload = """
                {"type":"ACCOUNT_REQUESTED","id":"%s","accountId":"%s"}
                """.formatted(event, applicant);
        listener.receive(payload);
        listener.receive(payload);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
        UUID delivery = jdbc.queryForObject("SELECT id FROM notification_delivery", UUID.class);
        var sender = mock(FirebasePushClient.class);
        when(sender.send(eq(token), eq(delivery.toString()), eq(administrator.toString()), anyString(), anyString()))
                .thenReturn(FirebasePushClient.Outcome.RETRY, FirebasePushClient.Outcome.SENT);

        scheduler(sender, now).deliver();
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM notification_delivery", Integer.class));
        assertTrue(deliveries.claim(now.plusSeconds(30), now.plusSeconds(120)).isEmpty());
        scheduler(sender, now.plusSeconds(60)).deliver();
        assertEquals("SENT", jdbc.queryForObject("SELECT state FROM notification_delivery", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM notification_delivery", Integer.class));
    }

    @Test
    void schedulesAtDeviceLocalTimeAndRechecksTheRecordedPaymentBeforeSending() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var local = now.atZone(java.time.ZoneId.of("America/Fortaleza"));
        UUID owner = account("USER", "ACTIVE");
        String token = device(owner, now.minusSeconds(3600), "America/Fortaleza");
        device(owner, now.plusSeconds(5), "America/Fortaleza");
        UUID financing = payment(owner, local.toLocalDate(), local.toLocalTime(), now);
        var sender = mock(FirebasePushClient.class);
        var scheduler = scheduler(sender, now);
        scheduler.plan();
        scheduler.plan();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery", Integer.class));
        assertEquals("CAIXA", jdbc.queryForObject("SELECT payment_kind FROM notification_delivery", String.class));
        assertEquals(Timestamp.from(now), jdbc.queryForObject("SELECT scheduled_at FROM notification_delivery",
                Timestamp.class));
        jdbc.update("UPDATE finance_month SET caixa_status='PAID' WHERE financing_id=?", financing);
        scheduler.deliver();
        verifyNoInteractions(sender);
        assertEquals("CANCELED", jdbc.queryForObject("SELECT state FROM notification_delivery", String.class));
    }

    @Test
    void queuesAndSendsAnOpenPaymentWithoutInventingAnAmount() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var local = now.atZone(ZoneOffset.UTC);
        UUID owner = account("USER", "ACTIVE");
        String token = device(owner, now.minusSeconds(3600), "UTC");
        payment(owner, local.toLocalDate(), local.toLocalTime(), now);
        var sender = mock(FirebasePushClient.class);
        var scheduler = scheduler(sender, now);
        scheduler.plan();

        UUID delivery = jdbc.queryForObject("SELECT id FROM notification_delivery", UUID.class);
        when(sender.send(eq(token), eq(delivery.toString()), eq(owner.toString()), anyString(), anyString()))
                .thenReturn(FirebasePushClient.Outcome.SENT);
        scheduler.deliver();
        verify(sender).send(eq(token), eq(delivery.toString()), eq(owner.toString()), eq("Lembrete de pagamento"),
                eq("Confira o pagamento da CAIXA com vencimento em "
                        + local.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + "."));
        assertEquals("SENT", jdbc.queryForObject("SELECT state FROM notification_delivery", String.class));
    }

    private NotificationPushScheduler scheduler(FirebasePushClient sender, Instant now) {
        return new NotificationPushScheduler(deliveries, preferences, sender, clock(now));
    }

    private static Clock clock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private UUID account(String role, String status) {
        UUID id = UUID.randomUUID();
        String username = "qa_" + id.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO app_account(id,username,display_name,password_hash,role,status,must_change_password,email)
                VALUES (?,?,'Fixture','fixture',?,?,false,?)
                """, id, username, role, status, username + "@example.test");
        return id;
    }

    private String device(UUID owner, Instant registered, String zone) {
        UUID installation = UUID.randomUUID();
        String token = "test-token-" + installation;
        jdbc.update("""
                INSERT INTO notification_device(installation_id,account_id,auth_version,token,token_hash,
                    zone_id,registered_at,refreshed_at)
                VALUES (?,?,0,?,sha256(convert_to(?,'UTF8')),?,?,?)
                """, installation, owner, token, token, zone, Timestamp.from(registered), Timestamp.from(registered));
        return token;
    }

    private UUID payment(UUID owner, LocalDate due, LocalTime time, Instant now) {
        UUID financing = UUID.randomUUID();
        LocalDate competence = due.withDayOfMonth(1);
        jdbc.update("""
                INSERT INTO notification_preference(account_id,version,enabled,offsets,reminder_time,
                    after_due,weekly,show_amounts,caixa_day,enabled_since)
                VALUES (?,1,true,ARRAY[0]::smallint[],?,true,true,true,31,?)
                """, owner, time, Timestamp.from(now.minusSeconds(3600)));
        jdbc.update("""
                INSERT INTO financing(id,owner_id,version,financed_cents,released_cents,annual_nominal_rate,
                    tracking_start_month,expected_delivery_date)
                VALUES (?,?,0,19200000,8400000,7.5,?,?)
                """, financing, owner, competence, competence.plusMonths(1));
        jdbc.update("""
                INSERT INTO finance_month(financing_id,competence,caixa_due_date,caixa_status,builder_status)
                VALUES (?,?,?,'OPEN','NO_PAYMENT')
                """, financing, competence, due);
        return financing;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for integration tests.");
        }
        return value;
    }
}
