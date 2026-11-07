package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Accounts;
import br.com.minhaobra.service.SessionContextService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final SessionContextService sessions;

    public AccountController(SessionContextService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public Accounts.User account(Authentication authentication) {
        return sessions.me(authentication).user();
    }
}
