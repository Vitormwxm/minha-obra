package br.com.minhaobra.service;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.Deletions;
import br.com.minhaobra.repository.AccountDeletionRepository;
import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.security.SecretCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountDeletionService {
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final AccountDeletionRepository deletionRequests;
    private final SecretCrypto crypto;
    private final ObjectMapper json;

    public AccountDeletionService(AccountService accounts, AccountRepository accountRepository,
            AccountDeletionRepository deletionRequests, SecretCrypto crypto, ObjectMapper json) {
        this.accounts = accounts;
        this.accountRepository = accountRepository;
        this.deletionRequests = deletionRequests;
        this.crypto = crypto;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Deletions.State state(SessionPrincipal principal) {
        Account account = accounts.sessionAccount(principal).orElseThrow(ApiException::forbidden);
        requireUser(account, principal);
        return new Deletions.State(deletionRequests.pending(account.id()).orElse(null));
    }

    @Transactional
    public Deletions.State request(SessionPrincipal principal, UUID key) {
        Account account = accounts.currentForUpdate(principal);
        requireUser(account, principal);
        UUID owner = account.id();
        return idempotent(owner, "deletion-request", key, "", () -> {
            var request = deletionRequests.pending(owner).orElseGet(() -> {
                var created = deletionRequests.create(owner);
                accountRepository.event(owner, owner, "ACCOUNT_DELETION_REQUESTED");
                return created;
            });
            return new Deletions.State(request);
        });
    }

    @Transactional
    public Deletions.State cancel(SessionPrincipal principal, UUID requestId, UUID key) {
        Account account = accounts.currentForUpdate(principal);
        requireUser(account, principal);
        UUID owner = account.id();
        return idempotent(owner, "deletion-cancel", key, requestId.toString(), () -> {
            requireCurrentRequest(owner, requestId);
            deletionRequests.cancel(owner, requestId);
            accountRepository.event(owner, owner, "ACCOUNT_DELETION_CANCELED");
            return new Deletions.State(null);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Deletions.Page page(SessionPrincipal principal, int page, int size) {
        accounts.requireAdmin(principal, false);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50) {
            throw new ApiException(400, "INVALID_PAGE", "Informe uma página válida.");
        }
        return deletionRequests.page(page, size);
    }

    @Transactional
    public void approve(SessionPrincipal principal, UUID requestId, UUID key) {
        Account admin = accounts.requireAdmin(principal, true);
        idempotent(admin.id(), "deletion-approve", key, requestId.toString(), () -> {
            UUID owner = deletionRequests.requestOwner(requestId).orElseThrow(AccountDeletionService::notFound);
            Account target = accountRepository.byIdForUpdate(owner).orElseThrow(AccountDeletionService::notFound);
            if (target.isAdmin()) {
                throw ApiException.forbidden();
            }
            // The owner may have canceled while the administrator was waiting for this lock.
            requireCurrentRequest(owner, requestId);
            accountRepository.revokeSessions(owner);
            if (!deletionRequests.purge(owner)) {
                throw notFound();
            }
            accountRepository.event(admin.id(), admin.id(), "ACCOUNT_DELETION_APPROVED");
            return new Deletions.State(null);
        });
    }

    private void requireCurrentRequest(UUID owner, UUID requestId) {
        var pending = deletionRequests.pending(owner);
        if (pending.isEmpty() || !pending.get().id().equals(requestId)) {
            throw ApiException.conflict();
        }
    }

    private static void requireUser(Account account, SessionPrincipal principal) {
        if (account.isAdmin() || !"AUTHENTICATED".equals(AccountService.stage(account, principal.mfaVerified()))) {
            throw ApiException.forbidden();
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "DELETION_REQUEST_NOT_FOUND", "Solicitação de exclusão não encontrada.");
    }

    private Deletions.State idempotent(UUID actor, String operation, UUID key, String input,
            Supplier<Deletions.State> action) {
        String fingerprint = crypto.fingerprint(operation, input);
        var cached = accountRepository.cached(actor, operation, key);
        if (cached.isPresent()) {
            var result = cached.get();
            if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                    result.fingerprint().getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Esta chave já foi usada em outra solicitação.");
            }
            return json.readValue(result.response(), Deletions.State.class);
        }

        Deletions.State result = action.get();
        accountRepository.saveCached(actor, operation, key, fingerprint, json.writeValueAsString(result));
        return result;
    }
}
