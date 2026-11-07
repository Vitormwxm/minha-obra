package br.com.minhaobra.service;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.Accounts;
import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.security.AttemptLimiter;
import br.com.minhaobra.security.PasswordPolicy;
import br.com.minhaobra.security.SecretCrypto;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountService {
    private final AccountRepository repository;
    private final PasswordEncoder passwords;
    private final AttemptLimiter limits;
    private final SecretCrypto crypto;
    private final ObjectMapper json;
    private final String dummyHash;

    public AccountService(AccountRepository repository, PasswordEncoder passwords, AttemptLimiter limits,
            SecretCrypto crypto, ObjectMapper json) {
        this.repository = repository;
        this.passwords = passwords;
        this.limits = limits;
        this.crypto = crypto;
        this.json = json;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public Optional<Account> sessionAccount(SessionPrincipal principal) {
        return repository.byId(principal.accountId())
                .filter(a -> a.isActive() && a.authVersion() == principal.authVersion());
    }

    public static String stage(Account a, boolean mfaVerified) {
        if (a.mustChangePassword())
            return "CHANGE_PASSWORD";
        if (a.isAdmin() && !mfaVerified)
            return a.totpSecret() == null ? "MFA_SETUP" : "MFA_VERIFY";
        return "AUTHENTICATED";
    }

    public static List<GrantedAuthority> authorities(Account a, boolean mfaVerified) {
        String stage = stage(a, mfaVerified);
        return List.of(new SimpleGrantedAuthority("ROLE_" + ("AUTHENTICATED".equals(stage) ? a.role() : stage)));
    }

    public static Accounts.Session session(Account a, boolean mfaVerified) {
        return new Accounts.Session(Accounts.User.from(a), stage(a, mfaVerified));
    }

    @Transactional(readOnly = true)
    public Account login(String username, String password, String ip) {
        String normalized = normalizeUsername(username);
        limits.login(ip, normalized);
        var found = repository.byUsername(normalized);
        String hash = found.map(Account::passwordHash).orElse(dummyHash);
        boolean matched = PasswordPolicy.validInputForHash(password)
                && limits.hash(() -> passwords.matches(password, hash));
        if (!matched || found.isEmpty())
            throw ApiException.unauthorized();
        Account account = found.get();
        if (account.isPendingApproval())
            throw new ApiException(403, "ACCOUNT_PENDING_APPROVAL", "Sua solicitação aguarda aprovação do administrador.");
        if (!account.isActive())
            throw ApiException.unauthorized();
        return account;
    }

    @Transactional
    public Account changePassword(SessionPrincipal principal, String current, String replacement) {
        PasswordPolicy.validate(replacement);
        Account a = currentForUpdate(principal);
        String currentStage = stage(a, principal.mfaVerified());
        if (!"CHANGE_PASSWORD".equals(currentStage) && !"AUTHENTICATED".equals(currentStage))
            throw ApiException.forbidden();
        boolean matched = PasswordPolicy.validInputForHash(current)
                && limits.hash(() -> passwords.matches(current, a.passwordHash()));
        if (!matched)
            throw new ApiException(400, "CURRENT_PASSWORD_INVALID", "A senha atual não confere.");
        if (current.equals(replacement))
            throw new ApiException(400, "PASSWORD_UNCHANGED", "Escolha uma senha diferente da senha atual.");
        String hash = limits.hash(() -> passwords.encode(replacement));
        Account changed = repository.changePassword(a.id(), hash);
        repository.revokeSessions(a.id());
        repository.event(a.id(), a.id(), "PASSWORD_CHANGED");
        return changed;
    }

    @Transactional(readOnly = true)
    public Accounts.Page users(SessionPrincipal actor, int page, int size, String search) {
        requireAdmin(actor, false);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 50 || search.length() > 120)
            throw new ApiException(400, "INVALID_PAGE", "Informe uma página e busca válidas.");
        return repository.page(page, size, search.trim().toLowerCase(Locale.ROOT));
    }

    @Transactional
    public Accounts.Registration register(UUID requestKey, Accounts.Register request) {
        requireRequestKey(requestKey);
        String username = normalizeUsername(request.username());
        if (!username.matches("[a-z0-9][a-z0-9._-]{2,39}"))
            throw new ApiException(400, "INVALID_USERNAME",
                    "Use de 3 a 40 letras minúsculas, números, ponto, hífen ou sublinhado.");
        String name = request.displayName().trim();
        if (name.length() < 2 || name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(400, "INVALID_NAME", "Informe um nome de 2 a 120 caracteres.");
        PasswordPolicy.validate(request.password());
        String fingerprint = crypto.fingerprint("register", canonical(username, name, request.password()));
        repository.requestLock("register-key:" + requestKey);
        var cached = repository.cachedRegistration(requestKey);
        if (cached.isPresent())
            return replay(cached.get(), fingerprint, Accounts.Registration.class);

        repository.requestLock("register-username:" + username);
        if (repository.byUsername(username).isPresent())
            throw unavailableUsername();
        String hash = limits.hash(() -> passwords.encode(request.password()));
        UUID id = UUID.randomUUID();
        if (!repository.insertRequest(id, username, name, hash))
            throw unavailableUsername();
        repository.event(id, id, "ACCOUNT_REQUESTED");
        Accounts.Registration result = new Accounts.Registration("PENDING_APPROVAL");
        repository.saveCached(id, "register", requestKey, fingerprint, json.writeValueAsString(result));
        return result;
    }

    @Transactional
    public Accounts.User approve(SessionPrincipal actor, UUID target, UUID key, long version) {
        requireAdmin(actor, true);
        String operation = "approve:" + target;
        return idempotent(actor.accountId(), operation, key, crypto.fingerprint(operation, Long.toString(version)), () -> {
            Account account = editableUser(actor, target, version);
            if (!account.isPendingApproval())
                throw new ApiException(409, "ACCOUNT_NOT_PENDING", "Esta conta não aguarda aprovação.");
            Account approved = repository.status(target, "ACTIVE");
            repository.event(actor.accountId(), target, "ACCOUNT_APPROVED");
            return Accounts.User.from(approved);
        });
    }

    @Transactional
    public Accounts.User setStatus(SessionPrincipal actor, UUID target, UUID key, long version, boolean blocked) {
        requireAdmin(actor, true);
        String operation = (blocked ? "block:" : "unblock:") + target;
        return idempotent(actor.accountId(), operation, key, crypto.fingerprint(operation, Long.toString(version)),
                () -> {
                    Account a = editableUser(actor, target, version);
                    requireApproved(a);
                    String desired = blocked ? "BLOCKED" : "ACTIVE";
                    if (a.status().equals(desired))
                        return Accounts.User.from(a);
                    Account changed = repository.status(target, desired);
                    repository.revokeSessions(target);
                    repository.event(actor.accountId(), target, blocked ? "ACCOUNT_BLOCKED" : "ACCOUNT_UNBLOCKED");
                    return Accounts.User.from(changed);
                });
    }

    @Transactional
    public Accounts.User resetPassword(SessionPrincipal actor, UUID target, UUID key,
            Accounts.TemporaryPassword request) {
        requireAdmin(actor, true);
        PasswordPolicy.validate(request.temporaryPassword());
        String operation = "reset:" + target;
        return idempotent(actor.accountId(), operation, key,
                crypto.fingerprint(operation, canonical(Long.toString(request.version()), request.temporaryPassword())),
                () -> {
                    Account a = editableUser(actor, target, request.version());
                    requireApproved(a);
                    String hash = limits.hash(() -> passwords.encode(request.temporaryPassword()));
                    Account changed = repository.resetPassword(a.id(), hash);
                    repository.revokeSessions(target);
                    repository.event(actor.accountId(), target, "TEMPORARY_PASSWORD_RESET");
                    return Accounts.User.from(changed);
                });
    }

    public Account currentForUpdate(SessionPrincipal principal) {
        Account a = repository.byIdForUpdate(principal.accountId()).orElseThrow(ApiException::forbidden);
        if (!a.isActive() || a.authVersion() != principal.authVersion())
            throw new ApiException(401, "SESSION_EXPIRED", "Sua sessão expirou. Entre novamente.");
        return a;
    }

    public Account requireAdmin(SessionPrincipal principal, boolean lock) {
        Account a = lock ? currentForUpdate(principal) : sessionAccount(principal).orElseThrow(ApiException::forbidden);
        if (!a.isAdmin() || !"AUTHENTICATED".equals(stage(a, principal.mfaVerified())))
            throw ApiException.forbidden();
        return a;
    }

    private Account editableUser(SessionPrincipal actor, UUID target, long version) {
        if (actor.accountId().equals(target))
            throw ApiException.forbidden();
        Account a = repository.byIdForUpdate(target)
                .orElseThrow(() -> new ApiException(404, "ACCOUNT_NOT_FOUND", "Conta não encontrada."));
        if (a.isAdmin())
            throw ApiException.forbidden();
        if (a.version() != version)
            throw ApiException.conflict();
        return a;
    }

    private static void requireApproved(Account account) {
        if (account.isPendingApproval())
            throw new ApiException(409, "ACCOUNT_PENDING_APPROVAL", "Aprove a solicitação antes de alterar o acesso.");
    }

    private static ApiException unavailableUsername() {
        return new ApiException(409, "USERNAME_UNAVAILABLE", "Não foi possível usar este nome de usuário.");
    }

    private static void requireRequestKey(UUID key) {
        if (key == null)
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Informe uma chave válida para a operação.");
    }

    private Accounts.User idempotent(UUID actor, String operation, UUID key, String fingerprint,
            Supplier<Accounts.User> work) {
        requireRequestKey(key);
        repository.requestLock(actor + ":" + operation + ":" + key);
        var cached = repository.cached(actor, operation, key);
        if (cached.isPresent())
            return replay(cached.get(), fingerprint, Accounts.User.class);
        Accounts.User result = work.get();
        repository.saveCached(actor, operation, key, fingerprint, json.writeValueAsString(result));
        return result;
    }

    private <T> T replay(AccountRepository.CachedRequest cached, String fingerprint, Class<T> type) {
        if (!java.security.MessageDigest.isEqual(fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                cached.fingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Esta chave já foi usada em outra solicitação.");
        return json.readValue(cached.response(), type);
    }

    private static String canonical(String... fields) {
        StringBuilder value = new StringBuilder();
        for (String field : fields)
            value.append(field.length()).append(':').append(field);
        return value.toString();
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
