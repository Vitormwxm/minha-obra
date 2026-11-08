package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Deletions;
import br.com.minhaobra.service.AccountDeletionService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDeletionController {
    private final AccountDeletionService deletions;
    private final SessionContextService sessions;

    public AccountDeletionController(AccountDeletionService deletions, SessionContextService sessions) {
        this.deletions = deletions;
        this.sessions = sessions;
    }

    @GetMapping("/api/account/deletion")
    public Deletions.State state(Authentication auth) {
        return deletions.state(sessions.principal(auth));
    }

    @PostMapping("/api/account/deletion")
    public Deletions.State request(Authentication auth, @RequestHeader("Idempotency-Key") UUID key) {
        return deletions.request(sessions.principal(auth), key);
    }

    @PostMapping("/api/account/deletion/cancel")
    public Deletions.State cancel(Authentication auth, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Deletions.Cancel body) {
        return deletions.cancel(sessions.principal(auth), body.requestId(), key);
    }

    @GetMapping("/api/admin/deletions")
    public Deletions.Page page(Authentication auth, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return deletions.page(sessions.principal(auth), page, size);
    }

    @PostMapping("/api/admin/deletions/{requestId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication auth, @PathVariable UUID requestId,
            @RequestHeader("Idempotency-Key") UUID key) {
        deletions.approve(sessions.principal(auth), requestId, key);
    }
}
