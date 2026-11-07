package br.com.minhaobra.domain;

import java.time.Instant;
import java.util.UUID;

/** Internal persistence model. Never returned directly from HTTP endpoints. */
public record Account(UUID id, String username, String displayName, String passwordHash,
        String role, String status, boolean mustChangePassword, long authVersion, long version,
        String totpSecret, String totpPendingSecret, Instant totpPendingUntil,
        long totpLastCounter, Instant createdAt) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    @Override
    public String toString() {
        return "Account[id=" + id + ",role=" + role + ",status=" + status + "]";
    }
}
