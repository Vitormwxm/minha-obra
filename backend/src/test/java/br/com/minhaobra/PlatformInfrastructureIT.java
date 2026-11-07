package br.com.minhaobra;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformInfrastructureIT {

    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = requiredEnvironment("TEST_DATABASE_URL");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("TEST_DATABASE_URL must use real PostgreSQL (jdbc:postgresql:).");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> requiredEnvironment("TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> requiredEnvironment("TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("app.auth.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.add("app.events.enabled", () -> "false");
    }

    @AfterAll
    static void removeOnlyThisTestSchema() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        try (var connection = DriverManager.getConnection(url,
                requiredEnvironment("TEST_DATABASE_USERNAME"), requiredEnvironment("TEST_DATABASE_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @Test
    void usesPostgreSql18InsteadOfAnInMemorySubstitute() {
        int version = Integer.parseInt(jdbc.queryForObject("SHOW server_version_num", String.class));
        assertTrue(version >= 180000 && version < 190000, "The integration database must be PostgreSQL 18.");
    }

    @Test
    void migratesTheIsolatedSchemaAndValidatesTheInitializationMarker() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_bootstrap WHERE id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
    }

    @Test
    void exposesHealthyProbesWithoutInfrastructureDetails() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            var response = get(path);
            assertEquals(200, response.statusCode(), path);
            assertEquals("UP", JsonPath.read(response.body(), "$.status"), path);
            assertFalse(response.body().contains("components"), path);
            assertFalse(response.body().contains("jdbc:postgresql"), path);
        }
    }

    @Test
    void keepsConfigurationEndpointsAndProductRoutesUnpublished() throws Exception {
        assertEquals(401, get("/actuator/env").statusCode());
        assertEquals(401, get("/api").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required by mvn verify. Start a dedicated PostgreSQL 18 test database; integration tests are never skipped.");
        }
        return value;
    }
}
