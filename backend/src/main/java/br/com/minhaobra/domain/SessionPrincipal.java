package br.com.minhaobra.domain;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/**
 * No credential, password hash or MFA secret may be serialized into a session.
 */
public record SessionPrincipal(UUID accountId, long authVersion, boolean mfaVerified,
        long authenticatedAt) implements Principal, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return accountId.toString();
    }
}
