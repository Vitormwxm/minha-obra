package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Accounts;
import br.com.minhaobra.service.AccountService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {
    private final AccountService accounts;
    private final SessionContextService sessions;

    public AdminUsersController(AccountService accounts, SessionContextService sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @GetMapping
    public Accounts.Page list(Authentication auth, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search) {
        return accounts.users(sessions.principal(auth), page, size, search);
    }

    @PostMapping("/{id}/approve")
    public Accounts.User approve(Authentication auth, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Accounts.Version body) {
        return accounts.approve(sessions.principal(auth), id, key, body.version());
    }

    @PostMapping("/{id}/block")
    public Accounts.User block(Authentication auth, @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Accounts.Version body) {
        return accounts.setStatus(sessions.principal(auth), id, key, body.version(), true);
    }

    @PostMapping("/{id}/unblock")
    public Accounts.User unblock(Authentication auth, @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Accounts.Version body) {
        return accounts.setStatus(sessions.principal(auth), id, key, body.version(), false);
    }

    @PostMapping("/{id}/temporary-password")
    public Accounts.User password(Authentication auth, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Accounts.TemporaryPassword body) {
        return accounts.resetPassword(sessions.principal(auth), id, key, body);
    }
}
