package br.com.minhaobra;

import br.com.minhaobra.repository.AccountRepository;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinanceIT {
    private static final String SCHEMA = "it_finance_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "Test-Finance-2026!";
    private static final String FINANCE = "/api/finance";
    private static final String FINANCING = FINANCE + "/financing";
    private static final ZoneId ZONE = ZoneId.of("America/Fortaleza");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
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
        jdbc.execute("TRUNCATE app_account, spring_session, official_tr_rate CASCADE");
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
    void financialDataBelongsToTheSignedInOwnerEvenForAnAdministrator() throws Exception {
        assertStatus(401, new Browser().get(FINANCE));
        Browser owner = login("owner", "USER");
        Browser other = login("other", "USER");
        Browser admin = login("administrator", "ADMIN");

        assertStatus(403, owner.send("PUT", FINANCING, financing(null), UUID.randomUUID(), false));
        assertError(400, "INVALID_REQUEST", owner.send("PUT", FINANCING, financing(null), null, true));
        UUID key = UUID.randomUUID();
        assertStatus(200, owner.save(FINANCING, financing(null), key));
        assertTrue(owner.ledger().get("financing").get("id").isString());
        assertStatus(200, owner.save(month("2025-03"), row(null, "PAID", "NO_PAYMENT",
                payment("CAIXA", "2025-03-05", 150, null))));
        assertTrue(owner.get(FINANCE).headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(1, owner.ledger().get("months").size());

        assertTrue(other.ledger().get("financing").isNull());
        assertTrue(admin.ledger().get("months").isEmpty());
        assertError(404, "FINANCING_NOT_FOUND", admin.save(month("2025-03"), row(null, "OPEN", "OPEN")));
        assertStatus(200, other.save(FINANCING, financing(null), key));
        assertNotEquals(owner.ledger().get("financing").get("id"), other.ledger().get("financing").get("id"));
        assertError(404, "MONTH_NOT_FOUND", other.delete("2025-03", 1, UUID.randomUUID()));
    }

    @Test
    void revisionsAndIdempotencyPreventDuplicateWritesAndStaleEdits() throws Exception {
        Browser browser = login("owner", "USER");
        Map<String, Object> original = financing(null);
        UUID financingKey = UUID.randomUUID();
        var created = browser.save(FINANCING, original, financingKey);
        long financingVersion = revision(created);
        assertEquals(created.body(), browser.save(FINANCING, original, financingKey).body());
        assertError(409, "VERSION_CONFLICT", browser.save(FINANCING, original));
        var editedFinancing = financing(financingVersion);
        editedFinancing.put("subsidyCents", 2_000);
        assertTrue(revision(browser.save(FINANCING, editedFinancing)) > financingVersion);
        assertEquals(created.body(), browser.save(FINANCING, original, financingKey).body());
        assertError(409, "IDEMPOTENCY_CONFLICT", browser.save(FINANCING, editedFinancing, financingKey));

        UUID createKey = UUID.randomUUID();
        var originalMonth = row(null, "PAID", "NO_PAYMENT", payment("CAIXA", "2025-03-05", 250, null));
        long version = revision(browser.save(month("2025-03"), originalMonth, createKey));
        assertEquals(version, revision(browser.save(month("2025-03"), originalMonth, createKey)));
        var updated = row(version, "PAID", "NO_PAYMENT", payment("CAIXA", "2025-03-05", 350, null));
        assertError(409, "IDEMPOTENCY_CONFLICT", browser.save(month("2025-03"), updated, createKey));
        long updatedVersion = revision(browser.save(month("2025-03"), updated));
        assertError(409, "VERSION_CONFLICT", browser.save(month("2025-03"), updated));

        UUID deleteKey = UUID.randomUUID();
        assertError(409, "VERSION_CONFLICT", browser.delete("2025-03", version, deleteKey));
        assertStatus(204, browser.delete("2025-03", updatedVersion, deleteKey));
        assertStatus(204, browser.delete("2025-03", updatedVersion, deleteKey));
        long recreatedVersion = revision(browser.save(month("2025-03"), originalMonth));
        assertNotEquals(updatedVersion, recreatedVersion);
        assertError(409, "VERSION_CONFLICT", browser.delete("2025-03", updatedVersion, UUID.randomUUID()));
        assertStatus(204, browser.delete("2025-03", updatedVersion, deleteKey));
        assertEquals(recreatedVersion, browser.ledger().get("months").get(0).get("version").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM finance_payment", Integer.class));
        assertTrue(jdbc.queryForList("SELECT response_json FROM account_idempotency", String.class).stream()
                .allMatch(response -> json.readTree(response).size() == 1
                        && json.readTree(response).has("version")));
    }

    @Test
    void concurrentEditsOfOneRevisionAllowExactlyOneChange() throws Exception {
        Browser first = login("owner", "USER");
        Browser second = new Browser();
        assertStatus(200, second.send("POST", "/api/auth/login",
                Map.of("username", "owner", "password", PASSWORD), null, true));
        assertStatus(200, first.save(FINANCING, financing(null)));
        String path = month("2025-03");
        long version = revision(first.save(path, row(null, "OPEN", "OPEN")));
        var gate = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return first.save(path, row(version, "PAID", "NO_PAYMENT", payment("CAIXA", "2025-03-05", 200, null)));
            });
            var two = executor.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return second.save(path, row(version, "PAID", "NO_PAYMENT", payment("CAIXA", "2025-03-05", 300, null)));
            });
            var a = one.get(25, TimeUnit.SECONDS);
            var b = two.get(25, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409), List.of(a.statusCode(), b.statusCode()).stream().sorted().toList());
            assertError(409, "VERSION_CONFLICT", a.statusCode() == 409 ? a : b);
            JsonNode saved = first.ledger().get("months").get(0);
            assertEquals(a.statusCode() == 200 ? 200 : 300, saved.get("monthlyTotalCents").asLong());
            assertEquals(1, saved.get("payments").size());
        }
    }

    @Test
    void ledgerCountsRecordedPaymentsOnceAndDistinguishesGapsUnknownAndZero() throws Exception {
        Browser browser = login("owner", "USER");
        assertStatus(200, browser.save(FINANCING, financing(null)));
        assertStatus(200, browser.save(month("2025-03"), row(null, "PAID", "PAID",
                payment("CAIXA", "2025-03-05", 300, 10L),
                payment("CAIXA", "2025-03-07", 200, null),
                payment("CONSTRUTORA", "2025-04-07", 200, 20L))));
        assertEquals("[\"2025-01\",\"2025-02\"]", browser.ledger().get("missingMonths").toString());

        assertStatus(200, browser.save(month("2025-01"), row(null, "PAID", "NO_PAYMENT",
                payment("CAIXA", "2025-01-05", 200, null))));
        long februaryVersion = revision(browser.save(month("2025-02"), row(null, "OPEN", "OPEN",
                payment("CONSTRUTORA", "2025-02-05", 100, null))));
        JsonNode months = browser.ledger().get("months");
        JsonNode january = months.get(0);
        JsonNode february = months.get(1);
        JsonNode march = months.get(2);
        assertTrue(january.get("closed").asBoolean());
        assertEquals(0, january.get("builderPaidCents").asLong());
        assertFalse(february.get("closed").asBoolean());
        assertTrue(february.get("caixaPaidCents").isNull());
        assertEquals(100, february.get("builderPaidCents").asLong());
        assertTrue(february.get("monthlyTotalCents").isNull());
        assertEquals(500, march.get("caixaPaidCents").asLong());
        assertEquals(200, march.get("builderPaidCents").asLong());
        assertEquals(700, march.get("monthlyTotalCents").asLong());
        assertEquals(700, march.get("caixaAccumulatedCents").asLong());
        assertEquals(300, march.get("builderAccumulatedCents").asLong());
        assertEquals(1_000, march.get("accumulatedPaidCents").asLong());
        assertTrue(march.get("monthlyVariationCents").isNull());

        long closedFebruary = revision(browser.save(month("2025-02"), row(februaryVersion, "NO_PAYMENT", "PAID",
                payment("CONSTRUTORA", "2025-02-05", 100, null))));
        months = browser.ledger().get("months");
        assertEquals(-100, months.get(1).get("monthlyVariationCents").asLong());
        assertEquals(600, months.get(2).get("monthlyVariationCents").asLong());
        assertEquals(1_000, months.get(2).get("accumulatedPaidCents").asLong());
        assertStatus(204, browser.delete("2025-02", closedFebruary, UUID.randomUUID()));
        JsonNode gap = browser.ledger();
        assertEquals("[\"2025-02\"]", gap.get("missingMonths").toString());
        assertTrue(gap.get("months").get(1).get("monthlyVariationCents").isNull());
    }

    @Test
    void validatesCoherentBalancesDatesAmountsAndStatuses() throws Exception {
        Browser browser = login("owner", "USER");
        long version = revision(browser.save(FINANCING, financing(null)));
        String path = month("2025-01");
        assertError(400, "INVALID_MONTH", browser.save(month("2101-01"), row(null, "OPEN", "OPEN")));
        assertError(400, "PAYMENT_STATUS_MISMATCH", browser.save(path, row(null, "PAID", "OPEN")));
        assertError(400, "PAYMENT_STATUS_MISMATCH", browser.save(path,
                row(null, "NO_PAYMENT", "OPEN", payment("CAIXA", "2025-01-05", 100, null))));
        assertError(400, "LATE_CHARGE_EXCEEDS_PAYMENT", browser.save(path,
                row(null, "PAID", "OPEN", payment("CAIXA", "2025-01-05", 100, 101L))));
        assertError(400, "FUTURE_PAYMENT", browser.save(path,
                row(null, "PAID", "OPEN", payment("CAIXA", LocalDate.now(ZONE).plusDays(1).toString(), 100, null))));
        var wrongDue = row(null, "OPEN", "OPEN");
        wrongDue.put("caixaDueDate", "2025-02-01");
        assertError(400, "DUE_DATE_OUTSIDE_MONTH", browser.save(path, wrongDue));
        wrongDue.put("caixaDueDate", "2025-01-01");
        wrongDue.put("releasedCents", 19_200_001);
        assertError(400, "RELEASED_EXCEEDS_FINANCED", browser.save(path, wrongDue));
        wrongDue.put("releasedCents", 2_000_000.1);
        assertError(400, "VALIDATION_FAILED", browser.save(path, wrongDue));
        assertTrue(browser.ledger().get("months").isEmpty());

        assertStatus(200, browser.save(path, row(null, "OPEN", "OPEN")));
        var changedStart = financing(version);
        changedStart.put("trackingStartMonth", "2025-02");
        assertError(400, "TRACKING_START_AFTER_ENTRY", browser.save(FINANCING, changedStart));
        assertError(400, "MONTH_BEFORE_TRACKING_START", browser.save(month("2024-12"), row(null, "OPEN", "OPEN")));

        String current = YearMonth.now(ZONE).toString();
        assertStatus(200, browser.save(month(current), row(null, "NO_PAYMENT", "NO_PAYMENT")));
        JsonNode currentMonth = browser.ledger().get("months").get(1);
        assertFalse(currentMonth.get("closed").asBoolean());
        assertEquals(0, currentMonth.get("monthlyTotalCents").asLong());
    }

    @Test
    void trUsesExactOfficialPeriodAndKeepsManualChoiceAcrossOlderClients() throws Exception {
        Browser browser = login("owner_tr", "USER");
        assertStatus(200, browser.save(FINANCING, financing(null)));
        String path = month("2025-03");
        var historical = row(null, "PAID", "NO_PAYMENT", payment("CAIXA", "2025-03-11", 15_889, null));
        historical.put("caixaDueDate", "2025-03-10");
        long version = revision(browser.save(path, historical));
        assertTrue(browser.ledger().get("months").get(0).get("tr").isNull());

        historical.put("payments", List.of(payment("CAIXA", "2025-03-11", 15_889, 70L)));
        historical.put("version", version);
        historical.put("trSettings", Map.of("periodStart", "2025-03-01"));
        assertError(400, "INVALID_TR_PERIOD", browser.save(path, historical));
        historical.put("trSettings", Map.of("periodStart", "2025-03-01", "periodEnd", "2025-04-01"));
        version = revision(browser.save(path, historical));
        var tr = browser.ledger().get("months").get(0).get("tr");
        assertEquals("ESTIMATED", tr.get("origin").asString());
        assertEquals("0.100012", tr.get("rate").asString());

        jdbc.update("""
                INSERT INTO official_tr_rate (period_start, period_end, rate_percent)
                VALUES (DATE '2025-03-01', DATE '2025-03-31', 0.0771), (DATE '2025-03-01', DATE '2025-04-01', 0.1092)
                """);
        var official = browser.send("POST", FINANCE + "/tr/official",
                Map.of("startDate", "2025-03-01", "endDate", "2025-04-01"), null, true);
        assertStatus(200, official);
        assertEquals("0.1092", json.readTree(official.body()).get("rate").asString());
        tr = browser.ledger().get("months").get(0).get("tr");
        assertEquals("OFFICIAL", tr.get("origin").asString());
        assertEquals("0.1092", tr.get("rate").asString());

        historical.put("version", version);
        historical.put("trSettings", Map.of("periodStart", "2025-03-01", "periodEnd", "2025-04-01", "manualRate", "0"));
        version = revision(browser.save(path, historical));
        historical.put("version", version);
        historical.remove("trSettings");
        version = revision(browser.save(path, historical));
        tr = browser.ledger().get("months").get(0).get("tr");
        assertEquals("MANUAL", tr.get("origin").asString());
        assertEquals("0", tr.get("rate").asString());

        historical.put("version", version);
        historical.put("trSettings", Map.of("periodStart", "2025-03-01", "periodEnd", "2025-04-01"));
        assertStatus(200, browser.save(path, historical));
        assertEquals("OFFICIAL", browser.ledger().get("months").get(0).get("tr").get("origin").asString());
    }

    private Browser login(String username, String role) throws Exception {
        var account = accounts.insert(UUID.randomUUID(), username, "Pessoa de teste", passwords.encode(PASSWORD), role);
        jdbc.update("UPDATE app_account SET must_change_password = FALSE WHERE id = ?", account.id());
        String recovery = "ABCDEF0123456789ABCDEF0123456789";
        if (role.equals("ADMIN")) {
            jdbc.update("UPDATE app_account SET totp_secret = ? WHERE id = ?",
                    crypto.encrypt("totp:" + account.id(), totp.generateSecret()), account.id());
            accounts.addRecovery(account.id(), crypto.fingerprint("recovery:" + account.id(), recovery));
        }
        Browser browser = new Browser();
        assertStatus(200, browser.send("POST", "/api/auth/login", Map.of("username", username, "password", PASSWORD),
                null, true));
        if (role.equals("ADMIN")) {
            assertStatus(200, browser.send("POST", "/api/auth/mfa/verify", Map.of("code", recovery), null, true));
        }
        return browser;
    }

    private static Map<String, Object> financing(Long version) {
        var request = new LinkedHashMap<String, Object>();
        request.put("version", version);
        request.put("financedCents", 19_200_000);
        request.put("releasedCents", 1_000_000);
        request.put("annualNominalRate", "8.25");
        request.put("expectedDeliveryDate", "2027-12-31");
        request.put("trackingStartMonth", "2025-01");
        request.put("subsidyCents", null);
        return request;
    }

    @SafeVarargs
    private static Map<String, Object> row(Long version, String caixa, String builder, Map<String, Object>... payments) {
        var request = new LinkedHashMap<String, Object>();
        request.put("version", version);
        request.put("caixaStatus", caixa);
        request.put("builderStatus", builder);
        request.put("releasedCents", 2_000_000);
        request.put("insuranceCents", 33);
        request.put("feesCents", 22);
        request.put("payments", List.of(payments));
        return request;
    }

    private static Map<String, Object> payment(String kind, String date, long cents, Long lateCents) {
        var request = new LinkedHashMap<String, Object>();
        request.put("kind", kind);
        request.put("paidOn", date);
        request.put("paidCents", cents);
        request.put("lateChargeCents", lateCents);
        return request;
    }

    private static String month(String month) {
        return FINANCE + "/months/" + month;
    }

    private long revision(HttpResponse<String> response) {
        assertStatus(200, response);
        return json.readTree(response.body()).get("version").asLong();
    }

    private void assertError(int status, String code, HttpResponse<String> response) {
        assertStatus(status, response);
        assertEquals(code, json.readTree(response.body()).get("code").asString(), response.body());
    }

    private static void assertStatus(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
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

        JsonNode ledger() throws Exception {
            var response = get(FINANCE);
            assertStatus(200, response);
            return json.readTree(response.body());
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> save(String path, Map<String, Object> body) throws Exception {
            return save(path, body, UUID.randomUUID());
        }

        HttpResponse<String> save(String path, Map<String, Object> body, UUID key) throws Exception {
            return send("PUT", path, body, key, true);
        }

        HttpResponse<String> delete(String month, long version, UUID key) throws Exception {
            return send("DELETE", month(month), Map.of("version", version), key, true);
        }

        HttpResponse<String> send(String method, String path, Object body, UUID key, boolean csrf) throws Exception {
            var builder = request(path).header("Content-Type", "application/json");
            if (csrf) {
                assertStatus(200, get("/api/auth/csrf"));
                var token = cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.hasExpired())
                        .findFirst().orElseThrow();
                builder.header("X-XSRF-TOKEN", token.getValue());
            }
            if (key != null) {
                builder.header("Idempotency-Key", key.toString());
            }
            return http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        }
    }
}
