package br.com.minhaobra.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 / HMAC-SHA1, six digits and 30-second steps. Persist accepted counters atomically. */
public final class Totp {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final long PERIOD = 30;
    private static final String ISSUER = "Minha Obra";
    private final SecureRandom random = new SecureRandom();

    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        try {
            return encodeBase32(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public String generateCode(String secret, Instant instant) {
        Objects.requireNonNull(instant, "Instante obrigatório.");
        if (instant.getEpochSecond() < 0) throw new IllegalArgumentException("Instante inválido.");
        return codeAtCounter(secret, instant.getEpochSecond() / PERIOD);
    }

    /**
     * Returns the matching, not-yet-consumed counter. The repository must compare/update
     * its stored counter in the same transaction to reject concurrent replay.
     */
    public OptionalLong verifyCounter(String secret, String code, Instant instant, long lastAcceptedCounter) {
        Objects.requireNonNull(instant, "Instante obrigatório.");
        if (code == null || !code.matches("[0-9]{6}") || instant.getEpochSecond() < 0) {
            return OptionalLong.empty();
        }
        long current = instant.getEpochSecond() / PERIOD;
        long matched = -1;
        byte[] provided = code.getBytes(StandardCharsets.US_ASCII);
        // Check the full window even when a match is found; select the highest matching
        // counter so an extremely rare same-code collision cannot be consumed twice.
        for (long candidate = Math.max(0, current - 1); candidate <= current + 1; candidate++) {
            byte[] expected = codeAtCounter(secret, candidate).getBytes(StandardCharsets.US_ASCII);
            boolean matches = MessageDigest.isEqual(expected, provided);
            if (matches && candidate > lastAcceptedCounter) matched = candidate;
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    public String otpauthUri(String secret, String username) {
        byte[] decoded = decodeBase32(secret);
        Arrays.fill(decoded, (byte) 0);
        if (username == null || username.isBlank() || username.length() > 120) {
            throw new IllegalArgumentException("Identificação inválida.");
        }
        return "otpauth://totp/" + url(ISSUER) + ":" + url(username)
                + "?secret=" + secret + "&issuer=" + url(ISSUER) + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String codeAtCounter(String secret, long counter) {
        byte[] key = decodeBase32(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Autenticação em duas etapas indisponível.");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder encoded = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                encoded.append(ALPHABET[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) encoded.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        return encoded.toString();
    }

    private static byte[] decodeBase32(String encoded) {
        // The enrollment implementation produces exactly 160-bit, unpadded secrets.
        if (encoded == null || !encoded.matches("[A-Z2-7]{32}")) {
            throw new IllegalArgumentException("Segredo de autenticação inválido.");
        }
        byte[] bytes = new byte[20];
        int buffer = 0;
        int bits = 0;
        int output = 0;
        for (int index = 0; index < encoded.length(); index++) {
            char value = encoded.charAt(index);
            int digit = value >= 'A' && value <= 'Z' ? value - 'A' : value - '2' + 26;
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bytes[output++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        return bytes;
    }
}
