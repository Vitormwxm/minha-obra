package br.com.minhaobra.service;

import static br.com.minhaobra.service.FirebasePushClient.Outcome.INVALID_TOKEN;
import static br.com.minhaobra.service.FirebasePushClient.Outcome.PERMANENT_FAILURE;
import static br.com.minhaobra.service.FirebasePushClient.Outcome.RETRY;
import static br.com.minhaobra.service.FirebasePushClient.Outcome.SENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

class FirebasePushClientTest {
    private static final Instant NOW = Instant.parse("2026-11-09T18:00:00Z");
    private static final URI ENDPOINT = URI.create("https://fcm.googleapis.com/v1/projects/minha-obra-test/messages:send");
    private static final String ACCOUNT = "aa534f4d-daf5-41b1-ac04-6464026cb8ea";
    private static final String DELIVERY = "ac4da040-0b5f-49b7-a1a7-3016442bfba1";
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = mock(Clock.class);
    private final HttpClient http = mock(HttpClient.class);
    private final FirebasePushClient client = new FirebasePushClient(json, clock, ENDPOINT,
            OAuth2Credentials.create(new AccessToken("fixture-oauth", Date.from(Instant.now().plusSeconds(3600)))), http);

    @Test
    void targetsOnlyTheAppAndLeavesDisplayToTheAuthenticatedDevice() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        serve(200, "{}", Map.of());

        assertEquals(SENT, send());

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(requestCaptor.capture(), ArgumentMatchers.<BodyHandler<byte[]>>any());
        var request = requestCaptor.getValue();
        assertEquals(ENDPOINT, request.uri());
        assertEquals("Bearer fixture-oauth", request.headers().firstValue("Authorization").orElseThrow());
        var message = json.readTree(readBody(request)).path("message");
        assertEquals("fixture-registration-token", message.path("token").stringValue());
        assertEquals(ACCOUNT, message.path("data").path("accountId").stringValue());
        assertEquals(DELIVERY, message.path("data").path("deliveryId").stringValue());
        assertEquals("br.com.minhaobra.app", message.path("android").path("restricted_package_name").stringValue());
        assertEquals("600s", message.path("android").path("ttl").stringValue());
        assertFalse(message.has("notification"));
        assertFalse(message.path("android").has("notification"));
    }

    @Test
    void retiresATokenOnlyWhenFcmExplicitlySaysItWasUnregistered() {
        when(clock.instant()).thenReturn(NOW);
        serve(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}", Map.of());
        assertEquals(PERMANENT_FAILURE, send());

        serve(404, """
                {"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",
                "errorCode":"UNREGISTERED"}]}}
                """, Map.of());
        assertEquals(INVALID_TOKEN, send());
    }

    @Test
    void waitsForRetryAfterBeforeMakingAnotherFirebaseRequest() {
        when(clock.instant()).thenReturn(NOW);
        serve(429, "{}", Map.of("Retry-After", List.of("90")));
        assertEquals(RETRY, send());
        when(clock.instant()).thenReturn(NOW.plusSeconds(89));
        assertEquals(RETRY, send());

        when(clock.instant()).thenReturn(NOW.plusSeconds(90));
        serve(200, "{}", Map.of());
        assertEquals(SENT, send());
        verify(http, times(2)).sendAsync(any(HttpRequest.class), ArgumentMatchers.<BodyHandler<byte[]>>any());
    }

    @Test
    void rejectsCredentialFilesThatPointToAnUntrustedTokenServer(@TempDir Path folder) throws Exception {
        var credentials = folder.resolve("credentials.json");
        Files.writeString(credentials, """
                {"type":"service_account","project_id":"minha-obra-test",
                "token_uri":"https://example.invalid/token"}
                """);
        var failure = assertThrows(IllegalStateException.class,
                () -> new FirebasePushClient(json, clock, "minha-obra-test", credentials.toString()));
        assertEquals("Configure uma credencial de serviço Firebase válida para este projeto.", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void loadsAServiceAccountKeyWithoutContactingFirebaseAtStartup(@TempDir Path folder) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var bytes = generator.generateKeyPair().getPrivate().getEncoded();
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        String pem = "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        var credentials = folder.resolve("credentials.json");
        Files.writeString(credentials, json.writeValueAsString(Map.of(
                "type", "service_account",
                "project_id", "minha-obra-test",
                "client_id", "1234567890",
                "client_email", "push@minha-obra-test.iam.gserviceaccount.com",
                "private_key_id", "fixture-key-id",
                "private_key", pem,
                "token_uri", "https://oauth2.googleapis.com/token",
                "universe_domain", "googleapis.com")));
        var configuredClient = new FirebasePushClient(json, clock, "minha-obra-test", credentials.toString());
        configuredClient.stop();
    }

    private FirebasePushClient.Outcome send() {
        return client.send("fixture-registration-token", DELIVERY, ACCOUNT, "Minha Obra", "Confira seu pagamento.");
    }

    @SuppressWarnings("unchecked")
    private void serve(int status, String body, Map<String, List<String>> headers) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(http.sendAsync(any(HttpRequest.class), ArgumentMatchers.<BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private static byte[] readBody(HttpRequest request) throws Exception {
        var output = new ByteArrayOutputStream();
        var completed = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer bytes) {
                var data = new byte[bytes.remaining()];
                bytes.get(data);
                output.writeBytes(data);
            }

            @Override
            public void onError(Throwable error) {
                completed.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                completed.complete(output.toByteArray());
            }
        });
        return completed.get(1, TimeUnit.SECONDS);
    }
}
