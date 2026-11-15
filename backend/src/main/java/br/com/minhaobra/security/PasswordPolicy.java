package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (!validInputForHash(password) || password.codePointCount(0, password.length()) < 8
                || password.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "PASSWORD_POLICY", "Use pelo menos 8 caracteres, até 72 bytes, sem caracteres de controle.");
        }
    }

    // Previously stored passwords remain valid up to BCrypt's input limit.
    public static boolean validInputForHash(String password) {
        return password != null && password.length() <= 256 && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
