package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void rejectsBlankPasswordsAndPasswordsBeyondBcryptsUtf8Limit() {
        assertThrows(ApiException.class,()->PasswordPolicy.validate("            "));
        assertThrows(ApiException.class,()->PasswordPolicy.validate("\t".repeat(12)));
        assertThrows(ApiException.class,()->PasswordPolicy.validate("é".repeat(37)));
        assertThrows(ApiException.class,()->PasswordPolicy.validate("a".repeat(65)));
        assertThrows(ApiException.class,()->PasswordPolicy.validate("🔐".repeat(6)));
    }
    @Test void preservesLeadingSpacesAndAllowsUnicodeWithinTheExplicitLimits() {
        assertDoesNotThrow(()->PasswordPolicy.validate("  Keep-these-spaces  "));
        assertDoesNotThrow(()->PasswordPolicy.validate("é".repeat(36)));
        assertDoesNotThrow(()->PasswordPolicy.validate("🔐".repeat(12)));
        assertDoesNotThrow(()->PasswordPolicy.validate("a".repeat(59)+"🔐".repeat(3)));
    }
}
