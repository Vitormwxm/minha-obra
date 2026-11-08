package br.com.minhaobra.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Deletions {
    private Deletions() {
    }

    public record Request(UUID id, Instant requestedAt) {
    }

    public record State(Request request) {
    }

    public record Cancel(@NotNull UUID requestId) {
    }

    public record AdminRequest(UUID id, Instant requestedAt, UUID accountId, String username,
            String displayName, String email) {
        @Override
        public String toString() {
            return "AdminRequest[id=" + id + "]";
        }
    }

    public record Page(List<AdminRequest> items, int page, int size, long total) {
    }
}
