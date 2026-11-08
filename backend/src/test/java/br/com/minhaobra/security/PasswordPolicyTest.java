package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void acceptsExactlyEightAsciiDigitsIncludingLeadingZeros() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("01234567"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("00000000"));
        for (String value : new String[] {
                null, "", "1234567", "123456789", "1234567x", " 01234567", "01234567\n", "１２３４５６７８"}) {
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
