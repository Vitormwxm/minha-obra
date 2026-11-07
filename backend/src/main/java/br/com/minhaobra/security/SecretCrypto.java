package br.com.minhaobra.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small authenticated envelope for persisted MFA secrets; never logs its inputs. */
public final class SecretCrypto {
    private static final String VERSION = "v1:";
    private static final String DEFAULT_CONTEXT = "totp-secret";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Chave criptográfica inválida.");
        }
        byte[] ownedKey = key.clone();
        encryptionKey = new SecretKeySpec(ownedKey, "AES");
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(ownedKey, "HmacSHA256"));
            byte[] derived = derivation.doFinal("minha-obra:fingerprint-key:v1".getBytes(StandardCharsets.UTF_8));
            fingerprintKey = new SecretKeySpec(derived, "HmacSHA256");
            Arrays.fill(derived, (byte) 0);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Criptografia indisponível.");
        } finally {
            Arrays.fill(ownedKey, (byte) 0);
        }
    }

    public String encrypt(String plaintext) {
        return encrypt(DEFAULT_CONTEXT, plaintext);
    }

    /** A context such as "totp:<account UUID>" additionally binds the envelope to its owner. */
    public String encrypt(String context, String plaintext) {
        Objects.requireNonNull(plaintext, "Conteúdo obrigatório.");
        byte[] aad = associatedData(context);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] cleartext = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(cleartext);
            byte[] envelope = ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array();
            return VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Não foi possível proteger o segredo.");
        } finally {
            Arrays.fill(cleartext, (byte) 0);
        }
    }

    public String decrypt(String envelope) {
        return decrypt(DEFAULT_CONTEXT, envelope);
    }

    public String decrypt(String context, String envelope) {
        byte[] aad = associatedData(context);
        if (envelope == null || !envelope.startsWith(VERSION) || envelope.length() > 65_536) {
            throw invalidEnvelope();
        }
        byte[] cleartext = null;
        try {
            byte[] packed = Base64.getUrlDecoder().decode(envelope.substring(VERSION.length()));
            if (packed.length < NONCE_BYTES + TAG_BITS / 8) {
                throw invalidEnvelope();
            }
            byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            cleartext = cipher.doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES);
            return new String(cleartext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw invalidEnvelope();
        } finally {
            if (cleartext != null) Arrays.fill(cleartext, (byte) 0);
        }
    }

    /** Keyed request fingerprint; unlike plain hashing it does not expose password guesses. */
    public String fingerprint(String context, String payload) {
        byte[] contextBytes = associatedData(context);
        Objects.requireNonNull(payload, "Conteúdo obrigatório.");
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(contextBytes.length).array());
            mac.update(contextBytes);
            return HexFormat.of().formatHex(mac.doFinal(payloadBytes));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Criptografia indisponível.");
        } finally {
            Arrays.fill(payloadBytes, (byte) 0);
        }
    }

    private static byte[] associatedData(String context) {
        if (context == null || context.isBlank() || context.length() > 256) {
            throw new IllegalArgumentException("Contexto criptográfico inválido.");
        }
        return ("minha-obra:secret:v1\0" + context).getBytes(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalidEnvelope() {
        return new IllegalArgumentException("Segredo protegido inválido.");
    }
}
