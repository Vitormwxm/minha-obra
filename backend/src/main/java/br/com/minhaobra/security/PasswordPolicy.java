package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class PasswordPolicy {
    private static final Pattern NEW_PASSWORD = Pattern.compile("[0-9]{8}");

    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || !NEW_PASSWORD.matcher(password).matches()) {
            throw new ApiException(400, "PASSWORD_POLICY", "Use uma senha com exatamente 8 dígitos de 0 a 9.");
        }
    }

    // Previously stored passwords remain valid up to BCrypt's input limit.
    public static boolean validInputForHash(String password) {
        return password != null && password.length() <= 256 && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
