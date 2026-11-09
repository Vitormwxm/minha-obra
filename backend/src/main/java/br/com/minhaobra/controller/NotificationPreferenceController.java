package br.com.minhaobra.controller;

import br.com.minhaobra.dto.NotificationPreferences;
import br.com.minhaobra.service.NotificationPreferenceService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/preferences")
public class NotificationPreferenceController {
    private final NotificationPreferenceService preferences;
    private final SessionContextService sessions;

    public NotificationPreferenceController(NotificationPreferenceService preferences, SessionContextService sessions) {
        this.preferences = preferences;
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<NotificationPreferences.View> get(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.get(sessions.principal(auth)));
    }

    @PutMapping
    public ResponseEntity<NotificationPreferences.View> save(Authentication auth,
            @Valid @RequestBody NotificationPreferences.Input input, @RequestHeader("Idempotency-Key") UUID key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(preferences.save(sessions.principal(auth), key, input));
    }
}
