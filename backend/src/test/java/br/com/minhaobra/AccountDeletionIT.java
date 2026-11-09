package br.com.minhaobra;

import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.repository.NotificationDeviceRepository;
import br.com.minhaobra.security.SecretCrypto;
import br.com.minhaobra.security.Totp;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountDeletionIT {
    private static final String SCHEMA = "it_deletion_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "05827461";
    private static final String REQUEST = "/api/account/deletion";
    private static final String ADMIN_REQUESTS = "/api/admin/deletions";
    private static final String NOTIFICATIONS = "/api/notifications/preferences";
    private static final String FINANCE = "/api/finance";
    private static final String FINANCING = FINANCE + "/financing";
    private static final String MONTH = FINANCE + "/months/2025-03";
    private static final ZoneId ZONE = ZoneId.of("America/Fortaleza");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired NotificationDeviceRepository devices;
    @Autowired PasswordEncoder passwords;
    @Autowired SecretCrypto crypto;
    @Autowired Totp totp;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = required("TEST_DATABASE_URL");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("Real PostgreSQL is required.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> required("TEST_DATABASE_PASSWORD"));
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("app.auth.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("app.events.enabled", () -> "false");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE app_account, spring_session, account_audit, account_outbox CASCADE");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(required("TEST_DATABASE_URL"),
                required("TEST_DATABASE_USERNAME"), required("TEST_DATABASE_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void pendingRequestsSuspendOwnerWritesAndCanBeCanceled() throws Exception {
        Browser owner = loginFixture("waiting_deletion", "USER");
        Browser other = loginFixture("other_writer", "USER");
        assertTrue(body(200, owner.get(REQUEST)).get("request").isNull());
        assertStatus(401, new Browser().get(REQUEST));
        assertStatus(403, owner.get(ADMIN_REQUESTS));

        assertStatus(200, owner.mutate("PUT", FINANCING, financing(null)));
        long monthVersion = body(200, owner.mutate("PUT", MONTH, month())).get("version").asLong();

        UUID requestKey = UUID.randomUUID();
        JsonNode pending = body(200, owner.send("POST", REQUEST, null, requestKey, true));
        String id = pending.get("request").get("id").asString();
        assertTrue(pending.get("request").get("requestedAt").isString());
        assertEquals(pending, body(200, owner.send("POST", REQUEST, null, requestKey, true)));
        assertEquals(pending, body(200, owner.mutate("POST", REQUEST, null)));
        assertEquals(pending, body(200, owner.get(REQUEST)));
        assertStatus(403, owner.send("POST", REQUEST, null, UUID.randomUUID(), false));

        assertError(409, "VERSION_CONFLICT", other.mutate("POST", REQUEST + "/cancel", Map.of("requestId", id)));
        assertStatus(200, other.mutate("PUT", FINANCING, financing(null)));
        assertStatus(200, owner.get("/api/account"));
        assertStatus(200, owner.get(FINANCE));
        assertStatus(200, owner.send("POST", FINANCE + "/projection", Map.of(), null, true));
        assertError(409, "ACCOUNT_DELETION_PENDING", owner.mutate("PUT", FINANCING, financing(0L)));
        assertError(409, "ACCOUNT_DELETION_PENDING", owner.mutate("PUT", MONTH, month()));
        assertError(409, "ACCOUNT_DELETION_PENDING", owner.mutate("DELETE", MONTH, Map.of("version", monthVersion)));

        UUID cancelKey = UUID.randomUUID();
        var cancel = Map.of("requestId", id);
        assertTrue(body(200, owner.send("POST", REQUEST + "/cancel", cancel, cancelKey, true)).get("request").isNull());
        assertTrue(body(200, owner.send("POST", REQUEST + "/cancel", cancel, cancelKey, true)).get("request").isNull());
        assertStatus(200, owner.mutate("PUT", FINANCING, financing(0L)));
        String next = body(200, owner.mutate("POST", REQUEST, null)).get("request").get("id").asString();
        assertNotEquals(id, next);
        assertError(409, "VERSION_CONFLICT", owner.mutate("POST", REQUEST + "/cancel", cancel));
        assertEquals(next, body(200, owner.get(REQUEST)).get("request").get("id").asString());
    }

    @Test
    void administratorCanPurgeOnlyTheRequestedAccountAndRepeatTheApproval() throws Exception {
        Browser admin = loginFixture("deletion_admin", "ADMIN");
        assertStatus(403, admin.mutate("POST", REQUEST, null));
        Browser other = loginFixture("other_account", "USER");
        assertStatus(200, other.mutate("PUT", FINANCING, financing(null)));

        String username = "deletion_target";
        Browser owner = new Browser();
        assertStatus(201, owner.mutate("POST", "/api/auth/register",
                Map.of("username", username, "displayName", "Pessoa de teste", "email", "exclusao@example.com",
                        "password", PASSWORD)));
        UUID ownerId = jdbc.queryForObject("SELECT id FROM app_account WHERE username=?", UUID.class, username);
        assertStatus(200, admin.mutate("POST", "/api/admin/users/" + ownerId + "/approve", Map.of("version", 0)));
        owner.login(username);
        assertStatus(200, owner.mutate("PUT", NOTIFICATIONS, notificationInput()));
        devices.register(UUID.randomUUID(), ownerId, 0, "fixture-device-to-delete", "America/Fortaleza");
        assertStatus(200, owner.mutate("PUT", FINANCING, financing(null)));
        assertStatus(200, owner.mutate("PUT", MONTH, month()));
        UUID financingId = jdbc.queryForObject("SELECT id FROM financing WHERE owner_id=?", UUID.class, ownerId);
        List<String> sessions = jdbc.queryForList("SELECT primary_id FROM spring_session WHERE principal_name=?",
                String.class, ownerId.toString());
        assertFalse(sessions.isEmpty());

        String requestId = body(200, owner.mutate("POST", REQUEST, null)).get("request").get("id").asString();
        JsonNode queue = body(200, admin.get(ADMIN_REQUESTS));
        assertEquals(1, queue.get("total").asInt());
        JsonNode entry = queue.get("items").get(0);
        assertEquals(requestId, entry.get("id").asString());
        assertEquals(ownerId.toString(), entry.get("accountId").asString());
        assertEquals("exclusao@example.com", entry.get("email").asString());
        assertFalse(entry.has("financing"));
        assertFalse(entry.has("passwordHash"));

        String approval = ADMIN_REQUESTS + "/" + requestId + "/approve";
        UUID approvalKey = UUID.randomUUID();
        assertStatus(403, other.send("POST", approval, null, approvalKey, true));
        assertStatus(204, admin.send("POST", approval, null, approvalKey, true));
        assertStatus(204, admin.send("POST", approval, null, approvalKey, true));
        assertError(409, "IDEMPOTENCY_CONFLICT",
                admin.send("POST", ADMIN_REQUESTS + "/" + UUID.randomUUID() + "/approve", null, approvalKey, true));
        assertError(404, "DELETION_REQUEST_NOT_FOUND", admin.mutate("POST", approval, null));
        assertStatus(401, owner.get(FINANCE));
        assertStatus(401, owner.send("POST", "/api/auth/login",
                Map.of("username", username, "password", PASSWORD), null, true));

        assertEquals(0, count("SELECT count(*) FROM app_account WHERE id=?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM account_deletion_request WHERE account_id=?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM notification_preference WHERE account_id=?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM notification_device WHERE account_id=?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM financing WHERE owner_id=?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM finance_month WHERE financing_id=?", financingId));
        assertEquals(0, count("SELECT count(*) FROM finance_payment WHERE financing_id=?", financingId));
        assertEquals(0, count("SELECT count(*) FROM account_audit WHERE actor_id=? OR account_id=?", ownerId, ownerId));
        assertEquals(0, count("SELECT count(*) FROM account_outbox WHERE event_key=?", ownerId));
        assertEquals(0, count("SELECT count(*) FROM account_idempotency WHERE actor_id=? OR operation=?",
                ownerId, "approve:" + ownerId));
        assertEquals(0, count("SELECT count(*) FROM spring_session WHERE principal_name=?", ownerId.toString()));
        for (String session : sessions) {
            assertEquals(0, count("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id=?", session));
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name) VALUES (?,?,0,0,1800,1800000,?)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), ownerId.toString()));
        assertEquals(0, body(200, admin.get(ADMIN_REQUESTS)).get("total").asInt());
        assertStatus(200, admin.get("/api/account"));
        assertFalse(body(200, other.get(FINANCE)).get("financing").isNull());
        assertStatus(204, other.send("POST", "/api/auth/logout", null, null, true));
        assertStatus(401, other.get(FINANCE));
    }

    @Test
    void notificationPreferencesStayPrivateAndRespectVersionsAndPendingDeletion() throws Exception {
        Browser owner = loginFixture("notification_owner", "USER");
        Browser other = loginFixture("notification_other", "USER");
        Browser admin = loginFixture("notification_admin", "ADMIN");
        assertStatus(401, new Browser().get(NOTIFICATIONS));
        assertStatus(403, admin.get(NOTIFICATIONS));

        JsonNode initial = body(200, owner.get(NOTIFICATIONS));
        assertEquals(0, initial.get("version").asInt());
        assertFalse(initial.get("enabled").asBoolean());
        assertEquals(json.readTree("[3,1,0]"), initial.get("offsets"));
        assertEquals("09:00", initial.get("time").asString());
        assertTrue(initial.get("afterDue").asBoolean());
        assertFalse(initial.get("weekly").asBoolean());
        assertTrue(initial.get("showAmounts").asBoolean());
        assertTrue(initial.get("caixaDay").isNull());
        assertTrue(initial.get("builderDay").isNull());

        var input = notificationInput();
        UUID key = UUID.randomUUID();
        assertStatus(403, owner.send("PUT", NOTIFICATIONS, input, key, false));
        JsonNode saved = body(200, owner.send("PUT", NOTIFICATIONS, input, key, true));
        assertEquals(1, saved.get("version").asInt());
        assertTrue(saved.get("enabled").asBoolean());
        assertEquals(json.readTree("[9,0]"), saved.get("offsets"));
        assertEquals("14:35", saved.get("time").asString());
        assertEquals(31, saved.get("caixaDay").asInt());
        assertEquals(saved, body(200, owner.get(NOTIFICATIONS)));
        assertEquals(saved, body(200, owner.send("PUT", NOTIFICATIONS, input, key, true)));
        assertError(409, "VERSION_CONFLICT", owner.mutate("PUT", NOTIFICATIONS, input));
        assertEquals(initial, body(200, other.get(NOTIFICATIONS)));

        input.put("version", 1);
        input.put("offsets", List.of(0, 0));
        assertError(400, "DUPLICATE_NOTIFICATION_OFFSET", owner.mutate("PUT", NOTIFICATIONS, input));
        input.put("offsets", List.of(0, 1.5));
        assertStatus(400, owner.mutate("PUT", NOTIFICATIONS, input));
        input.put("offsets", List.of(0, 2));
        assertError(409, "IDEMPOTENCY_CONFLICT", owner.send("PUT", NOTIFICATIONS, input, key, true));
        assertStatus(200, owner.mutate("POST", REQUEST, null));
        assertError(409, "ACCOUNT_DELETION_PENDING", owner.mutate("PUT", NOTIFICATIONS, input));
        assertEquals(saved, body(200, owner.get(NOTIFICATIONS)));
    }

    @Test
    void deviceRevocationIsOwnedByTheAuthenticatedAccount() throws Exception {
        Browser owner = loginFixture("device_owner", "USER");
        Browser other = loginFixture("device_other", "USER");
        UUID ownerId = jdbc.queryForObject("SELECT id FROM app_account WHERE username='device_owner'", UUID.class);
        UUID installation = UUID.randomUUID();
        String path = "/api/notifications/devices/" + installation;
        String token = "fixture-token-" + "a".repeat(3000);
        var input = Map.of("token", token, "timeZone", "America/Fortaleza");
        assertStatus(403, owner.send("PUT", path, input, null, false));
        assertError(503, "PUSH_UNAVAILABLE", owner.mutate("PUT", path, input));

        devices.register(installation, ownerId, 0, token, "America/Fortaleza");
        devices.register(installation, ownerId, 0, token, "America/Fortaleza");
        assertStatus(204, other.mutate("DELETE", path, null));
        assertEquals(1, count("SELECT count(*) FROM notification_device WHERE installation_id=?", installation));
        assertStatus(204, owner.send("POST", "/api/auth/logout", null, null, true, installation));
        assertEquals(0, count("SELECT count(*) FROM notification_device WHERE installation_id=?", installation));
        assertStatus(401, owner.get(FINANCE));
    }

    private static Map<String, Object> notificationInput() {
        var input = new LinkedHashMap<String, Object>();
        input.put("version", 0);
        input.put("enabled", true);
        input.put("offsets", List.of(0, 9));
        input.put("time", "14:35");
        input.put("afterDue", false);
        input.put("weekly", true);
        input.put("showAmounts", false);
        input.put("caixaDay", 31);
        input.put("builderDay", null);
        return input;
    }

    private int count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Integer.class, parameters);
    }

    private Browser loginFixture(String username, String role) throws Exception {
        var account = accounts.insert(UUID.randomUUID(), username, "Pessoa de teste", passwords.encode(PASSWORD), role);
        jdbc.update("UPDATE app_account SET must_change_password=FALSE WHERE id=?", account.id());
        String recovery = "ABCDEF0123456789ABCDEF0123456789";
        if ("ADMIN".equals(role)) {
            jdbc.update("UPDATE app_account SET totp_secret=? WHERE id=?",
                    crypto.encrypt("totp:" + account.id(), totp.generateSecret()), account.id());
            accounts.addRecovery(account.id(), crypto.fingerprint("recovery:" + account.id(), recovery));
        }
        Browser browser = new Browser();
        browser.login(username);
        if ("ADMIN".equals(role)) {
            assertStatus(200, browser.send("POST", "/api/auth/mfa/verify", Map.of("code", recovery), null, true));
        }
        return browser;
    }

    private static Map<String, Object> financing(Long version) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        payload.put("financedCents", 19_200_000);
        payload.put("releasedCents", 8_400_000);
        payload.put("annualNominalRate", "8.25");
        payload.put("expectedDeliveryDate", LocalDate.now(ZONE).plusMonths(2).toString());
        payload.put("trackingStartMonth", "2025-01");
        return payload;
    }

    private static Map<String, Object> month() {
        return Map.of("caixaStatus", "PAID", "builderStatus", "NO_PAYMENT", "payments",
                List.of(Map.of("kind", "CAIXA", "paidOn", "2025-03-05", "paidCents", 25_000)));
    }

    private JsonNode body(int status, HttpResponse<String> response) {
        assertStatus(status, response);
        return json.readTree(response.body());
    }

    private void assertError(int status, String code, HttpResponse<String> response) {
        assertEquals(code, body(status, response).get("code").asString());
    }

    private static void assertStatus(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " required; integration tests are not skipped.");
        }
        return value;
    }

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();

        void login(String username) throws Exception {
            assertStatus(200, send("POST", "/api/auth/login", Map.of("username", username, "password", PASSWORD),
                    null, true));
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> mutate(String method, String path, Object payload) throws Exception {
            return send(method, path, payload, UUID.randomUUID(), true);
        }

        HttpResponse<String> send(String method, String path, Object payload, UUID key, boolean csrf) throws Exception {
            return send(method, path, payload, key, csrf, null);
        }

        HttpResponse<String> send(String method, String path, Object payload, UUID key, boolean csrf,
                UUID installation) throws Exception {
            var request = request(path);
            if (csrf) {
                assertStatus(200, get("/api/auth/csrf"));
                var token = cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.hasExpired())
                        .findFirst().orElseThrow();
                request.header("X-XSRF-TOKEN", token.getValue());
            }
            if (key != null) {
                request.header("Idempotency-Key", key.toString());
            }
            if (installation != null) {
                request.header("X-Notification-Installation", installation.toString());
            }
            var publisher = payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
            return http.send(request.header("Content-Type", "application/json").method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        }
    }
}
