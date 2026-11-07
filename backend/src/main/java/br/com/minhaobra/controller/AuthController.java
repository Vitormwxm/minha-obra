package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Accounts;
import br.com.minhaobra.service.AccountService;
import br.com.minhaobra.service.MfaService;
import br.com.minhaobra.service.SessionContextService;
import br.com.minhaobra.security.AttemptLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts;
    private final MfaService mfa;
    private final SessionContextService sessions;
    private final AttemptLimiter limits;

    public AuthController(AccountService accounts, MfaService mfa, SessionContextService sessions,
            AttemptLimiter limits) {
        this.accounts = accounts;
        this.mfa = mfa;
        this.sessions = sessions;
        this.limits = limits;
    }

    @GetMapping("/csrf")
    public Accounts.Csrf csrf(CsrfToken token) {
        return new Accounts.Csrf(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    public Accounts.Session login(@Valid @RequestBody Accounts.Login body, HttpServletRequest request,
            HttpServletResponse response) {
        return sessions.start(accounts.login(body.username(), body.password(), request.getRemoteAddr()), false, request,
                response);
    }

    @GetMapping("/me")
    public Accounts.Session me(Authentication authentication) {
        return sessions.me(authentication);
    }

    @PostMapping("/change-password")
    public Accounts.Session change(@Valid @RequestBody Accounts.ChangePassword body, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        var principal = sessions.principal(authentication);
        limits.mfa(request.getRemoteAddr(), "password:" + principal.getName());
        return sessions.start(accounts.changePassword(principal, body.currentPassword(), body.newPassword()), false,
                request, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
    }

    @PostMapping("/mfa/setup")
    public Accounts.MfaSetup setup(Authentication authentication, HttpServletRequest request) {
        return mfa.setup(sessions.principal(authentication), request.getRemoteAddr());
    }

    @PostMapping("/mfa/confirm")
    public Accounts.MfaConfirmed confirm(@Valid @RequestBody Accounts.Code body, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        var enrolled = mfa.confirm(sessions.principal(authentication), body.code(), request.getRemoteAddr());
        return new Accounts.MfaConfirmed(sessions.start(enrolled.account(), true, request, response),
                enrolled.recoveryCodes());
    }

    @PostMapping("/mfa/verify")
    public Accounts.Session verify(@Valid @RequestBody Accounts.Code body, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        return sessions.start(mfa.verify(sessions.principal(authentication), body.code(), request.getRemoteAddr()),
                true, request, response);
    }
}
