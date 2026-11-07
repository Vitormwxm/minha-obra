package br.com.minhaobra.security;

import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionValidationFilter extends OncePerRequestFilter {
    private final AccountService accounts;private final Clock clock;
    SessionValidationFilter(AccountService accounts,Clock clock) {this.accounts=accounts;this.clock=clock;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control","no-store");
        var context=SecurityContextHolder.getContext();
        var authentication=context.getAuthentication();
        if(authentication!=null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            var account=accounts.sessionAccount(principal);
            if(account.isEmpty() || clock.millis()-principal.authenticatedAt()>=8*60*60*1000L) {
                var session=request.getSession(false);if(session!=null)session.invalidate();
                SecurityContextHolder.clearContext();
                if(!request.getRequestURI().startsWith("/api/") || request.getRequestURI().equals("/api/auth/csrf")
                        || request.getRequestURI().equals("/api/auth/login") || request.getRequestURI().equals("/api/auth/register")) {
                    chain.doFilter(request,response);return;
                }
                SecurityResponses.error(response,401,"SESSION_EXPIRED","Sua sessão expirou. Entre novamente.");return;
            }
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,AccountService.authorities(account.get(),principal.mfaVerified())));
        }
        chain.doFilter(request,response);
    }
}
