package br.com.minhaobra.service;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.domain.NotificationPreference;
import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.NotificationPreferences;
import br.com.minhaobra.repository.AccountDeletionRepository;
import br.com.minhaobra.repository.AccountRepository;
import br.com.minhaobra.repository.NotificationPreferenceRepository;
import br.com.minhaobra.security.SecretCrypto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class NotificationPreferenceService {
    private static final String OPERATION = "notification-preferences";
    private final AccountService accounts;
    private final AccountDeletionRepository deletionRequests;
    private final AccountRepository requests;
    private final NotificationPreferenceRepository preferences;
    private final SecretCrypto crypto;
    private final ObjectMapper json;

    public NotificationPreferenceService(AccountService accounts, AccountDeletionRepository deletionRequests,
            AccountRepository requests, NotificationPreferenceRepository preferences, SecretCrypto crypto,
            ObjectMapper json) {
        this.accounts = accounts;
        this.deletionRequests = deletionRequests;
        this.requests = requests;
        this.preferences = preferences;
        this.crypto = crypto;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public NotificationPreferences.View get(SessionPrincipal principal) {
        Account owner = accounts.sessionAccount(principal)
                .orElseThrow(() -> new ApiException(401, "SESSION_EXPIRED", "Sua sessão expirou. Entre novamente."));
        requireUser(owner, principal);
        return NotificationPreferences.View.from(preferences.byOwner(owner.id())
                .orElseGet(NotificationPreference::defaults));
    }

    @Transactional
    public NotificationPreferences.View save(SessionPrincipal principal, UUID key, NotificationPreferences.Input input) {
        Account owner = accounts.currentForUpdate(principal);
        requireUser(owner, principal);
        if (deletionRequests.pending(owner.id()).isPresent()) {
            throw new ApiException(409, "ACCOUNT_DELETION_PENDING",
                    "Cancele o pedido de exclusão da conta para alterar suas preferências.");
        }

        var offsets = input.offsets().stream().map(BigDecimal::intValueExact).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        if (offsets.size() != input.offsets().size()) {
            throw new ApiException(400, "DUPLICATE_NOTIFICATION_OFFSET", "Informe apenas uma vez cada antecedência.");
        }

        var next = new NotificationPreference(input.version().longValueExact() + 1, input.enabled(), offsets,
                LocalTime.parse(input.time()), input.afterDue(), input.weekly(), input.showAmounts(),
                optionalDay(input.caixaDay()), optionalDay(input.builderDay()));
        var view = NotificationPreferences.View.from(next);
        String body = json.writeValueAsString(view);
        String fingerprint = crypto.fingerprint(OPERATION, body);
        var cached = requests.cached(owner.id(), OPERATION, key);
        if (cached.isPresent()) {
            if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                    cached.get().fingerprint().getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Esta chave já foi usada em outra solicitação.");
            }
            return json.readValue(cached.get().response(), NotificationPreferences.View.class);
        }

        long currentVersion = preferences.byOwner(owner.id()).map(NotificationPreference::version).orElse(0L);
        if (currentVersion != next.version() - 1) {
            throw ApiException.conflict();
        }
        preferences.save(owner.id(), next);
        requests.saveCached(owner.id(), OPERATION, key, fingerprint, body);
        return view;
    }

    private static Integer optionalDay(BigDecimal value) {
        return value == null ? null : value.intValueExact();
    }

    private static void requireUser(Account account, SessionPrincipal principal) {
        if (account.isAdmin() || !"AUTHENTICATED".equals(AccountService.stage(account, principal.mfaVerified()))) {
            throw ApiException.forbidden();
        }
    }
}
