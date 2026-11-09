package br.com.minhaobra.controller;

import br.com.minhaobra.dto.NotificationDevices;
import br.com.minhaobra.service.NotificationDeviceService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/devices/{installation}")
public class NotificationDeviceController {
    private final NotificationDeviceService devices;
    private final SessionContextService sessions;

    public NotificationDeviceController(NotificationDeviceService devices, SessionContextService sessions) {
        this.devices = devices;
        this.sessions = sessions;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(Authentication auth, @PathVariable UUID installation,
            @Valid @RequestBody NotificationDevices.Registration registration) {
        devices.register(sessions.principal(auth), installation, registration);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth, @PathVariable UUID installation) {
        devices.revoke(sessions.principal(auth), installation);
    }
}
