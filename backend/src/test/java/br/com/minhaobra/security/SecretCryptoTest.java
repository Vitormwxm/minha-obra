package br.com.minhaobra.security;

import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretCryptoTest {
    private static byte[] key(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }

    @Test
    void encryptsUtf8WithFreshNoncesAndDefensivelyCopiesItsKey() {
        byte[] input = key(1);
        SecretCrypto crypto = new SecretCrypto(input);
        Arrays.fill(input, (byte) 0);
        String plaintext = "segredo de teste: ação 🔐";
        String first = crypto.encrypt(plaintext);
        String second = crypto.encrypt(plaintext);
        assertTrue(first.startsWith("v1:"));
        assertFalse(first.contains(plaintext));
        assertNotEquals(first, second);
        assertEquals(plaintext, crypto.decrypt(first));
        assertEquals(plaintext, new SecretCrypto(key(1)).decrypt(second));
        assertEquals("", crypto.decrypt(crypto.encrypt("")));
        byte[] firstBytes = Base64.getUrlDecoder().decode(first.substring(3));
        byte[] secondBytes = Base64.getUrlDecoder().decode(second.substring(3));
        assertFalse(Arrays.equals(Arrays.copyOf(firstBytes, 12), Arrays.copyOf(secondBytes, 12)));
    }

    @Test
    void rejectsModifiedCiphertextWrongKeysAndCrossAccountEnvelopesWithoutLeakingInputs() {
        SecretCrypto crypto = new SecretCrypto(key(2));
        String envelope = crypto.encrypt("totp:account-a", "PRIVATE_TEST_SECRET");
        byte[] bytes = Base64.getUrlDecoder().decode(envelope.substring(3));
        bytes[bytes.length - 1] ^= 1;
        String modified = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        for (Runnable decrypt : new Runnable[]{
                () -> crypto.decrypt("totp:account-a", modified),
                () -> crypto.decrypt("totp:account-b", envelope),
                () -> new SecretCrypto(key(3)).decrypt("totp:account-a", envelope)}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, decrypt::run);
            assertEquals("Segredo protegido inválido.", error.getMessage());
            assertNull(error.getCause());
            assertFalse(error.toString().contains(envelope));
            assertFalse(error.toString().contains("PRIVATE_TEST_SECRET"));
        }
    }

    @Test
    void rejectsMalformedOrUnsupportedEnvelopesAndInvalidKeys() {
        SecretCrypto crypto = new SecretCrypto(key(4));
        for (String envelope : new String[]{"", "v2:AAAA", "v1:not base64!", "v1:AAAA", "v1:" + "a".repeat(65_536)}) {
            assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(envelope));
        }
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt((String) null));
        assertThrows(IllegalArgumentException.class, () -> new SecretCrypto(null));
        assertThrows(IllegalArgumentException.class, () -> new SecretCrypto(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new SecretCrypto(new byte[33]));
    }

    @Test
    void fingerprintsAreKeyedStableAndBoundToOperationAndCompletePayload() {
        SecretCrypto crypto = new SecretCrypto(key(5));
        String first = crypto.fingerprint("create-user:actor-a", "alice\0temporary-password");
        assertTrue(first.matches("[a-f0-9]{64}"));
        assertEquals(first, new SecretCrypto(key(5)).fingerprint("create-user:actor-a", "alice\0temporary-password"));
        assertNotEquals(first, crypto.fingerprint("create-user:actor-b", "alice\0temporary-password"));
        assertNotEquals(first, crypto.fingerprint("reset-password:actor-a", "alice\0temporary-password"));
        assertNotEquals(first, crypto.fingerprint("create-user:actor-a", "alice\0other-password"));
        assertNotEquals(first, new SecretCrypto(key(6)).fingerprint("create-user:actor-a", "alice\0temporary-password"));
        assertNotEquals(crypto.fingerprint("ab", "c"), crypto.fingerprint("a", "bc"));
    }
}
