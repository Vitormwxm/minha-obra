package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void acceptsAtLeastEightCharactersWithinTheHashLimit() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("01234567"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("MinhaObra2026"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("123456789"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("é".repeat(36)));
        for (String value : new String[] {
                null, "", "1234567", "        ", "01234567\n", "é".repeat(37), "a".repeat(73)}) {
            assertThrows(ApiException.class, () -> PasswordPolicy.validate(value));
        }
    }

    @Test void preservesTheHashInputLimitForExistingPasswords() {
        assertTrue(PasswordPolicy.validInputForHash("  Keep-these-spaces  "));
        assertTrue(PasswordPolicy.validInputForHash("é".repeat(36)));
        assertFalse(PasswordPolicy.validInputForHash("é".repeat(37)));
        assertFalse(PasswordPolicy.validInputForHash(null));
    }
}
