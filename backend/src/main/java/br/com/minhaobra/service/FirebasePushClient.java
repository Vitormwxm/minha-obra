package br.com.minhaobra.service;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "app.push.enabled", havingValue = "true")
public class FirebasePushClient {
    public enum Outcome { SENT, INVALID_TOKEN, RETRY, PERMANENT_FAILURE }

    private static final Logger LOG = LoggerFactory.getLogger(FirebasePushClient.class);
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String FCM_ERROR_TYPE = "type.googleapis.com/google.firebase.fcm.v1.FcmError";
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_DOCUMENT_BYTES = 32 * 1024;

    private final ObjectMapper json;
    private final Clock clock;
    private final URI endpoint;
    private final OAuth2Credentials credentials;
    private final HttpClient http;
    private final AtomicReference<Instant> retryNotBefore = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    public FirebasePushClient(ObjectMapper json, Clock clock,
            @Value("${app.push.project-id:}") String projectId,
            @Value("${app.push.credentials-file:}") String credentialsFile) {
        this(json, clock, endpoint(projectId), loadCredentials(json, projectId, credentialsFile),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    FirebasePushClient(ObjectMapper json, Clock clock, URI endpoint, OAuth2Credentials credentials, HttpClient http) {
        this.json = json;
        this.clock = clock;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.http = http;
    }

    public Outcome send(String token, String deliveryId, String accountId, String title, String body) {
        if (clock.instant().isBefore(retryNotBefore.get())) {
            return Outcome.RETRY;
        }
        Map<String, Object> payload = Map.of("message", Map.of(
                "token", token,
                "data", Map.of("deliveryId", deliveryId, "accountId", accountId, "title", title, "body", body),
                "android", Map.of("priority", "high", "ttl", "600s", "restricted_package_name", "br.com.minhaobra.app")));
        try {
            String authorization = credentials.getRequestMetadata(endpoint).get("Authorization").getFirst();
            var request = HttpRequest.newBuilder(endpoint).timeout(SEND_TIMEOUT)
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            var response = http.sendAsync(request,
                    info -> BodySubscribers.limiting(BodySubscribers.ofByteArray(), MAX_DOCUMENT_BYTES));
            try {
                return classify(response.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                response.cancel(true);
                Thread.currentThread().interrupt();
                return Outcome.RETRY;
            } catch (TimeoutException timeout) {
                response.cancel(true);
                return Outcome.RETRY;
            }
        } catch (ExecutionException | IOException unavailable) {
            return Outcome.RETRY;
        }
    }

    private Outcome classify(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 200) {
            return Outcome.SENT;
        }
        if (status == 429 || status == 503) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            Instant now = clock.instant();
            Instant next = retryAfter(retryAfter, now, status == 429 ? 60 : 10);
            retryNotBefore.accumulateAndGet(next, (current, candidate) ->
                    candidate.isAfter(current) ? candidate : current);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return Outcome.RETRY;
        }
        if (status == 404 && isUnregistered(response.body())) {
            return Outcome.INVALID_TOKEN;
        }
        LOG.warn("Firebase rejeitou o envio de uma notificação (HTTP {}).", status);
        return Outcome.PERMANENT_FAILURE;
    }

    private boolean isUnregistered(byte[] body) {
        try {
            for (JsonNode detail : json.readTree(body).path("error").path("details")) {
                if (FCM_ERROR_TYPE.equals(detail.path("@type").stringValue(null))
                        && "UNREGISTERED".equals(detail.path("errorCode").stringValue(null))) {
                    return true;
                }
            }
        } catch (JacksonException malformed) {
            return false;
        }
        return false;
    }

    private static Instant retryAfter(String value, Instant now, long fallbackSeconds) {
        Duration delay;
        try {
            delay = Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notDeltaSeconds) {
            try {
                delay = Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException invalidHeader) {
                delay = Duration.ofSeconds(fallbackSeconds);
            }
        }
        if (delay.compareTo(Duration.ofSeconds(10)) < 0) {
            delay = Duration.ofSeconds(10);
        }
        if (delay.compareTo(Duration.ofDays(1)) > 0) {
            delay = Duration.ofDays(1);
        }
        return now.plus(delay);
    }

    private static URI endpoint(String projectId) {
        if (!PROJECT_ID.matcher(projectId).matches()) {
            throw new IllegalStateException("Configure o ID do projeto Firebase em app.push.project-id.");
        }
        return URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send");
    }

    private static ServiceAccountCredentials loadCredentials(ObjectMapper json, String projectId, String filename) {
        byte[] document = null;
        try {
            Path path = Path.of(filename);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Expected a local credentials file");
            }
            try (var input = Files.newInputStream(path)) {
                document = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            }
            if (document.length > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("Credentials exceed the size limit");
            }
            var info = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(document);
            if (!"service_account".equals(info.path("type").stringValue(null))
                    || !projectId.equals(info.path("project_id").stringValue(null))
                    || !TOKEN_ENDPOINT.toString().equals(info.path("token_uri").stringValue(null))
                    || !"googleapis.com".equals(info.path("universe_domain").stringValue("googleapis.com"))) {
                throw new IllegalArgumentException("Unexpected service account configuration");
            }
            var transport = new NetHttpTransport.Builder().setConnectionFactory(url -> {
                if (!TOKEN_ENDPOINT.toString().equals(url.toExternalForm())) {
                    throw new IOException("Unexpected OAuth endpoint");
                }
                var connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                return connection;
            }).build();
            return ServiceAccountCredentials.fromStream(new ByteArrayInputStream(document), () -> transport)
                    .toBuilder().setScopes(List.of(SCOPE)).build().createWithCustomRetryStrategy(false);
        } catch (IOException | RuntimeException invalid) {
            // Parsing and transport exceptions can contain snippets of the credentials.
            throw new IllegalStateException("Configure uma credencial de serviço Firebase válida para este projeto.");
        } finally {
            if (document != null) {
                Arrays.fill(document, (byte) 0);
            }
        }
    }

    @PreDestroy
    void stop() {
        http.shutdownNow();
    }
}
