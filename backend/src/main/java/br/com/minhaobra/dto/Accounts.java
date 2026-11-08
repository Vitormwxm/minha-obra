package br.com.minhaobra.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import br.com.minhaobra.domain.Account;
import jakarta.validation.constraints.*;

public final class Accounts {
    private Accounts() {
    }

    public record User(UUID id, String username, String displayName, String email, String role, String status,
            boolean mustChangePassword, long version, Instant createdAt) {
        public static User from(Account a) {
            return new User(a.id(), a.username(), a.displayName(), a.email(), a.role(), a.status(), a.mustChangePassword(),
                    a.version(), a.createdAt());
        }

        @Override
        public String toString() {
            return "User[id=" + id + ",role=" + role + ",status=" + status + "]";
        }
    }

    public record Session(User user, String stage) {
    }

    public record Csrf(String token, String headerName) {
    }

    public record Login(@NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    public record ChangePassword(@NotBlank @Size(max = 256) String currentPassword,
            @NotNull @Size(max = 72) String newPassword) {
        @Override
        public String toString() {
            return "ChangePassword[redacted]";
        }
    }

    public record Register(@NotBlank @Size(min = 3, max = 40) String username,
            @NotBlank @Size(min = 2, max = 120) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(max = 72) String password) {
        public Register {
            email = email == null ? null : email.strip();
        }

        @Override
        public String toString() {
            return "Register[redacted]";
        }
    }

    public record Registration(String status) {
    }

    public record Version(@NotNull @PositiveOrZero Long version) {
    }

    public record TemporaryPassword(@NotNull @PositiveOrZero Long version,
            @NotNull @Size(max = 72) String temporaryPassword) {
        @Override
        public String toString() {
            return "TemporaryPassword[redacted]";
        }
    }

    public record Code(@NotBlank @Size(max = 80) String code) {
        @Override
        public String toString() {
            return "Code[redacted]";
        }
    }

    public record MfaSetup(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "MfaSetup[redacted]";
        }
    }

    public record MfaConfirmed(Session session, List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "MfaConfirmed[redacted]";
        }
    }

    public record Page(List<User> items, int page, int size, long total) {
    }
}
