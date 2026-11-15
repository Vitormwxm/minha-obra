package br.com.minhaobra.service;

import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.security.PasswordPolicy;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only provisioning and password recovery. */
public final class BootstrapAdminService {
    private final AccountRepository accounts;private final PasswordEncoder passwords;private final TransactionTemplate transaction;
    public BootstrapAdminService(AccountRepository accounts,PasswordEncoder passwords,TransactionTemplate transaction) {this.accounts=accounts;this.passwords=passwords;this.transaction=transaction;}
    public void create(String username,String password) {
        String normalized=AccountService.normalizeUsername(username);
        if(!normalized.matches("[a-z0-9][a-z0-9._-]{2,39}")) throw new ApiException(400,"INVALID_USERNAME","Nome de usuário inválido.");
        PasswordPolicy.validate(password);
        transaction.executeWithoutResult(status->{
            accounts.bootstrapLock();
            if(accounts.adminCount()!=0) throw new ApiException(409,"ADMIN_EXISTS","O administrador já foi criado; nenhuma conta foi alterada.");
            if(accounts.byUsername(normalized).isPresent()) throw new ApiException(409,"USERNAME_UNAVAILABLE","O nome já está em uso; nenhuma conta foi promovida.");
            var created=accounts.insert(UUID.randomUUID(),normalized,"Administrador",passwords.encode(password),"ADMIN");
            accounts.event(null,created.id(),"ADMIN_BOOTSTRAPPED");
        });
    }

    public void resetPassword(String username, String password) {
        String normalized = AccountService.normalizeUsername(username);
        PasswordPolicy.validate(password);
        transaction.executeWithoutResult(status -> {
            accounts.bootstrapLock();
            var account = accounts.byUsername(normalized)
                    .flatMap(found -> accounts.byIdForUpdate(found.id()))
                    .filter(found -> found.isAdmin() && found.isActive())
                    .orElseThrow(() -> new ApiException(404, "ADMIN_NOT_FOUND", "Administrador ativo não encontrado."));
            accounts.resetPassword(account.id(), passwords.encode(password));
            accounts.revokeSessions(account.id());
            accounts.event(null, account.id(), "ADMIN_PASSWORD_RESET_BY_OPERATOR");
        });
    }
}
