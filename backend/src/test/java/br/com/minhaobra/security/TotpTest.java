package br.com.minhaobra.security;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    // RFC 6238 Appendix B SHA1 key "12345678901234567890", encoded with RFC 4648 Base32.
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private final Totp totp = new Totp();

    @Test
    void agreesWithRfc6238Sha1VectorsTruncatedToSixDigits() {
        long[] timestamps = {59, 1_111_111_109L, 1_111_111_111L, 1_234_567_890L, 2_000_000_000L, 20_000_000_000L};
        String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
        for (int index = 0; index < timestamps.length; index++) {
            assertEquals(expected[index], totp.generateCode(RFC_SECRET, Instant.ofEpochSecond(timestamps[index])));
        }
    }

    @Test
    void acceptsOnlyCurrentAndAdjacentStepsAndRejectsAlreadyConsumedCounters() {
        Instant now = Instant.ofEpochSecond(1_234_567_890L);
        long counter = now.getEpochSecond() / 30;
        for (int offset = -1; offset <= 1; offset++) {
            String code = totp.generateCode(RFC_SECRET, now.plusSeconds(offset * 30L));
            assertEquals(counter + offset, totp.verifyCounter(RFC_SECRET, code, now, counter - 2).orElseThrow());
            assertTrue(totp.verifyCounter(RFC_SECRET, code, now, counter + offset).isEmpty());
        }
        for (int offset : new int[]{-2, 2}) {
            String code = totp.generateCode(RFC_SECRET, now.plusSeconds(offset * 30L));
            assertTrue(totp.verifyCounter(RFC_SECRET, code, now, -1).isEmpty());
        }
    }

    @Test
    void rejectsMalformedCodesAndSecretsInsteadOfCoercingThem() {
        Instant now = Instant.ofEpochSecond(1_234_567_890L);
        for (String code : new String[]{"", "00592", "0005924", "005 924", " 005924", "005924\n", "٠٠٥٩٢٤", "aaaaaa"}) {
            assertTrue(totp.verifyCounter(RFC_SECRET, code, now, -1).isEmpty());
        }
        assertTrue(totp.verifyCounter(RFC_SECRET, null, now, -1).isEmpty());
        assertTrue(totp.verifyCounter(RFC_SECRET, "005924", Instant.ofEpochSecond(-1), -1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> totp.generateCode("NOT_A_SECRET", now));
        assertThrows(IllegalArgumentException.class, () -> totp.generateCode(RFC_SECRET.toLowerCase(), now));
        assertThrows(IllegalArgumentException.class, () -> totp.generateCode(RFC_SECRET, Instant.ofEpochSecond(-1)));
    }

    @Test
    void generatesIndependent160BitSecretsUsableByTheVerifier() {
        var secrets = new HashSet<String>();
        Instant now = Instant.ofEpochSecond(1_234_567_890L);
        for (int index = 0; index < 100; index++) {
            String secret = totp.generateSecret();
            assertTrue(secret.matches("[A-Z2-7]{32}"));
            assertTrue(secrets.add(secret));
            assertTrue(totp.verifyCounter(secret, totp.generateCode(secret, now), now, -1).isPresent());
        }
    }

    @Test
    void enrollmentUriEscapesTheUsernameAndDescribesTheExactAlgorithm() {
        String username = "pessoa+teste@example.com";
        String enrollment = totp.otpauthUri(RFC_SECRET, username);
        URI uri = URI.create(enrollment);
        assertEquals("otpauth", uri.getScheme());
        assertEquals("totp", uri.getHost());
        assertEquals("/Minha Obra:" + username, URLDecoder.decode(uri.getRawPath(), StandardCharsets.UTF_8));
        String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(query.contains("secret=" + RFC_SECRET));
        assertTrue(query.contains("issuer=Minha Obra"));
        assertTrue(query.contains("algorithm=SHA1&digits=6&period=30"));
    }
}
