package br.com.minhaobra.service;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.Accounts;
import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.security.AttemptLimiter;
import br.com.minhaobra.security.SecretCrypto;
import br.com.minhaobra.security.Totp;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
    private final AccountService accounts;private final AccountRepository repository;
    private final Totp totp;private final SecretCrypto crypto;private final Clock clock;private final AttemptLimiter limits;
    private final SecureRandom random=new SecureRandom();
    public MfaService(AccountService accounts,AccountRepository repository,Totp totp,SecretCrypto crypto,Clock clock,AttemptLimiter limits) {
        this.accounts=accounts;this.repository=repository;this.totp=totp;this.crypto=crypto;this.clock=clock;this.limits=limits;
    }
    @Transactional public Accounts.MfaSetup setup(SessionPrincipal principal,String ip) {
        limits.mfa(ip,principal.getName());
        Account a=mfaAccount(principal);
        if(a.totpSecret()!=null) throw new ApiException(409,"MFA_ALREADY_CONFIGURED","O autenticador já está configurado.");
        String secret=totp.generateSecret();
        repository.pendingTotp(a.id(),crypto.encrypt("totp:"+a.id(),secret),clock.instant().plus(Duration.ofMinutes(10)));
        return new Accounts.MfaSetup(secret,totp.otpauthUri(secret,a.username()));
    }
    @Transactional public Enrollment confirm(SessionPrincipal principal,String code,String ip) {
        limits.mfa(ip,principal.getName());
        Account a=mfaAccount(principal);
        if(a.totpSecret()!=null) throw new ApiException(409,"MFA_ALREADY_CONFIGURED","O autenticador já está configurado.");
        if(a.totpPendingSecret()==null || a.totpPendingUntil()==null || !clock.instant().isBefore(a.totpPendingUntil()))
            throw new ApiException(400,"MFA_SETUP_EXPIRED","A configuração expirou. Gere uma nova chave.");
        String secret=crypto.decrypt("totp:"+a.id(),a.totpPendingSecret());
        var counter=totp.verifyCounter(secret,code,clock.instant(),-1);
        if(counter.isEmpty()) throw invalidCode();
        Account changed=repository.activateTotp(a.id(),a.totpPendingSecret(),counter.getAsLong());
        repository.clearRecovery(a.id());
        List<String> codes=new ArrayList<>();
        for(int i=0;i<10;i++) {
            byte[] bytes=new byte[16];random.nextBytes(bytes);
            String raw=HexFormat.of().withUpperCase().formatHex(bytes);
            String formatted=raw.substring(0,8)+"-"+raw.substring(8,16)+"-"+raw.substring(16,24)+"-"+raw.substring(24);
            codes.add(formatted);repository.addRecovery(a.id(),recoveryHash(a,formatted));
        }
        repository.revokeSessions(a.id());repository.event(a.id(),a.id(),"MFA_ENROLLED");
        return new Enrollment(changed,List.copyOf(codes));
    }
    @Transactional public Account verify(SessionPrincipal principal,String code,String ip) {
        limits.mfa(ip,principal.getName());
        Account a=mfaAccount(principal);
        if(a.totpSecret()==null || principal.mfaVerified()) throw new ApiException(409,"MFA_NOT_REQUIRED","Não há uma verificação pendente.");
        boolean accepted;
        if(code.matches("[0-9]{6}")) {
            var counter=totp.verifyCounter(crypto.decrypt("totp:"+a.id(),a.totpSecret()),code,clock.instant(),a.totpLastCounter());
            accepted=counter.isPresent() && repository.consumeCounter(a.id(),a.totpLastCounter(),counter.getAsLong());
        } else {
            String normalized=normalizeRecovery(code);
            accepted=normalized.matches("[A-F0-9]{32}") && repository.consumeRecovery(a.id(),recoveryHash(a,normalized));
        }
        if(!accepted) throw invalidCode();
        repository.event(a.id(),a.id(),"MFA_VERIFIED");
        return a;
    }
    private Account mfaAccount(SessionPrincipal principal) {
        Account a=accounts.currentForUpdate(principal);
        if(!a.isAdmin() || a.mustChangePassword()) throw ApiException.forbidden();
        return a;
    }
    private String recoveryHash(Account a,String code) {return crypto.fingerprint("recovery:"+a.id(),normalizeRecovery(code));}
    private static String normalizeRecovery(String code) {return code.replace("-","").trim().toUpperCase(Locale.ROOT);}
    private static ApiException invalidCode() {return new ApiException(401,"MFA_CODE_INVALID","Código inválido ou já utilizado. Tente um novo código.");}
    public record Enrollment(Account account,List<String> recoveryCodes) {
        @Override public String toString(){return "Enrollment[redacted]";}
    }
}
