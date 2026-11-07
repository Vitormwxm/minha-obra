package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String password) {
        int count=password==null?0:password.codePointCount(0,password.length());
        if(password==null || password.isBlank() || count<12 || count>64 || password.getBytes(StandardCharsets.UTF_8).length>72
                || password.codePoints().anyMatch(p->p>=0xD800 && p<=0xDFFF)) {
            throw new ApiException(400,"PASSWORD_POLICY","Use uma senha com 12 a 64 caracteres e até 72 bytes UTF-8.");
        }
    }
    public static boolean validInputForHash(String password) { return password!=null && password.length()<=256 && password.getBytes(StandardCharsets.UTF_8).length<=72; }
}
