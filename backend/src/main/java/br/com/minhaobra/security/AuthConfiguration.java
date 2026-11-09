package br.com.minhaobra.security;

import br.com.minhaobra.service.AccountService;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

@Configuration(proxyBeanMethods=false)
public class AuthConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean Totp totp() { return new Totp(); }
    @Bean SecretCrypto secretCrypto(@Value("${app.auth.encryption-key:}") String encoded) {
        byte[] key;
        try { key=Base64.getDecoder().decode(encoded); }
        catch(IllegalArgumentException invalid) { throw new IllegalStateException("Configure APP_TOTP_ENCRYPTION_KEY com 32 bytes em Base64."); }
        try {
            if(key.length!=32) throw new IllegalStateException("Configure APP_TOTP_ENCRYPTION_KEY com 32 bytes em Base64.");
            return new SecretCrypto(key);
        } finally { Arrays.fill(key,(byte)0); }
    }
    @Bean CookieCsrfTokenRepository csrfRepository(@Value("${app.auth.secure-cookies:false}") boolean secure) {
        CookieCsrfTokenRepository repository=CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie->cookie.secure(secure).sameSite("Lax"));
        return repository;
    }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),new CsrfAuthenticationStrategy(csrf)));
    }
    @Bean
    @Order(1)
    SecurityFilterChain publicProjectionSecurity(HttpSecurity http, Clock clock) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/public/projection"));
        // This route calculates a scenario without reading or changing accounts or sessions.
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(cache -> cache.disable());
        http.csrf(csrf -> csrf.disable());
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        http.addFilterBefore(new PublicProjectionAccessFilter(clock), SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean SecurityFilterChain applicationSecurity(HttpSecurity http,AccountService accounts,Clock clock,
            CookieCsrfTokenRepository csrf,SecurityContextRepository context) throws Exception {
        http.formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable());
        http.requestCache(cache->cache.disable());
        http.securityContext(config->config.securityContextRepository(context));
        http.sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        http.csrf(config->config.spa().csrfTokenRepository(csrf));
        http.exceptionHandling(errors->errors
            .authenticationEntryPoint((request,response,error)->SecurityResponses.error(response,401,"UNAUTHENTICATED","Entre na sua conta para continuar."))
            .accessDeniedHandler((request,response,error)->SecurityResponses.error(response,403,
                error instanceof org.springframework.security.web.csrf.CsrfException?"CSRF_INVALID":"FORBIDDEN",
                error instanceof org.springframework.security.web.csrf.CsrfException?"Atualize a sessão e tente novamente.":"Você não tem permissão para esta ação.")));
        http.authorizeHttpRequests(auth->auth
            .requestMatchers("/actuator/health","/actuator/health/**").permitAll()
            .requestMatchers(HttpMethod.GET,"/api/auth/csrf").permitAll()
            .requestMatchers(HttpMethod.POST,"/api/auth/login","/api/auth/register").permitAll()
            .requestMatchers("/api/auth/**").authenticated()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/**").hasAnyRole("USER","ADMIN")
            .anyRequest().denyAll());
        http.addFilterAfter(new SessionValidationFilter(accounts,clock),SecurityContextHolderFilter.class);
        return http.build();
    }
}
