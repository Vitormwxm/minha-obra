package br.com.minhaobra.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class NotificationDevices {
    private NotificationDevices() {
    }

    public record Registration(
            @NotBlank @Size(min = 16, max = 4096) @Pattern(regexp = "[A-Za-z0-9:_-]+") String token,
            @NotBlank @Size(max = 64) String timeZone) {
    }
}
