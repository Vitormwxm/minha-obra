package br.com.minhaobra.service;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.Accounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Service;

@Service
public class SessionContextService {
    private final SessionAuthenticationStrategy strategy;private final SecurityContextRepository contexts;
    private final CookieCsrfTokenRepository csrf;private final Clock clock;private final AccountService accounts;
    public SessionContextService(SessionAuthenticationStrategy strategy,SecurityContextRepository contexts,CookieCsrfTokenRepository csrf,Clock clock,AccountService accounts) {
        this.strategy=strategy;this.contexts=contexts;this.csrf=csrf;this.clock=clock;this.accounts=accounts;
    }
    public SessionPrincipal principal(Authentication authentication) {
        if(authentication==null || !(authentication.getPrincipal() instanceof SessionPrincipal p)) throw new ApiException(401,"UNAUTHENTICATED","Entre na sua conta para continuar.");
        return p;
    }
    public Accounts.Session me(Authentication authentication) {
        SessionPrincipal p=principal(authentication);
        Account a=accounts.sessionAccount(p).orElseThrow(()->new ApiException(401,"SESSION_EXPIRED","Sua sessão expirou. Entre novamente."));
        return AccountService.session(a,p.mfaVerified());
    }
    public Accounts.Session start(Account account,boolean mfaVerified,HttpServletRequest request,HttpServletResponse response) {
        var old=request.getSession(false);if(old!=null)old.invalidate();
        request.getSession(true);
        SessionPrincipal principal=new SessionPrincipal(account.id(),account.authVersion(),mfaVerified,clock.millis());
        var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,AccountService.authorities(account,mfaVerified));
        strategy.onAuthentication(authentication,request,response);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        return AccountService.session(account,mfaVerified);
    }
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        var session=request.getSession(false);if(session!=null)session.invalidate();
        var context=SecurityContextHolder.createEmptyContext();SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);csrf.saveToken(null,request,response);
    }
}
