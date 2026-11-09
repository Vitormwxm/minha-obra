package br.com.minhaobra.service;

import br.com.minhaobra.domain.SessionPrincipal;
import br.com.minhaobra.dto.NotificationDevices;
import br.com.minhaobra.repository.AccountDeletionRepository;
import br.com.minhaobra.repository.NotificationDeviceRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeviceService {
    private final AccountService accounts;
    private final AccountDeletionRepository deletions;
    private final NotificationDeviceRepository devices;
    private final boolean enabled;

    public NotificationDeviceService(AccountService accounts, AccountDeletionRepository deletions,
            NotificationDeviceRepository devices, @Value("${app.push.enabled:false}") boolean enabled) {
        this.accounts = accounts;
        this.deletions = deletions;
        this.devices = devices;
        this.enabled = enabled;
    }

    @Transactional
    public void register(SessionPrincipal principal, UUID installation, NotificationDevices.Registration input) {
        if (!enabled) {
            throw new ApiException(503, "PUSH_UNAVAILABLE", "O envio de notificações está indisponível.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(input.timeZone());
        } catch (DateTimeException invalidZone) {
            throw new ApiException(400, "INVALID_TIME_ZONE", "O fuso horário do aparelho não é reconhecido.");
        }
        var account = accounts.currentForUpdate(principal);
        if (!"AUTHENTICATED".equals(AccountService.stage(account, principal.mfaVerified()))) {
            throw ApiException.forbidden();
        }
        if (deletions.pending(account.id()).isPresent()) {
            throw new ApiException(409, "ACCOUNT_DELETION_PENDING",
                    "Cancele o pedido de exclusão da conta para vincular este aparelho.");
        }
        try {
            devices.register(installation, account.id(), account.authVersion(), input.token(), zone.getId());
        } catch (DuplicateKeyException duplicateToken) {
            throw new ApiException(409, "DEVICE_TOKEN_CONFLICT", "O identificador de notificações já está vinculado.");
        }
    }

    @Transactional
    public void revoke(SessionPrincipal principal, UUID installation) {
        devices.revoke(installation, principal.accountId());
    }
}
