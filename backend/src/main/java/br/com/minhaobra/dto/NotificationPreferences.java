package br.com.minhaobra.dto;

import br.com.minhaobra.domain.NotificationPreference;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class NotificationPreferences {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private NotificationPreferences() {
    }

    public record Input(
            @NotNull @Digits(integer = 16, fraction = 0) @Min(0) @Max(9_007_199_254_740_990L) BigDecimal version,
            @NotNull Boolean enabled,
            @NotNull @Size(max = 8) List<@NotNull @Digits(integer = 2, fraction = 0) @Min(0) @Max(30) BigDecimal> offsets,
            @NotNull @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String time,
            @NotNull Boolean afterDue,
            @NotNull Boolean weekly,
            @NotNull Boolean showAmounts,
            @Digits(integer = 2, fraction = 0) @Min(1) @Max(31) BigDecimal caixaDay,
            @Digits(integer = 2, fraction = 0) @Min(1) @Max(31) BigDecimal builderDay) {
    }

    public record View(long version, boolean enabled, List<Integer> offsets, String time, boolean afterDue,
            boolean weekly, boolean showAmounts, Integer caixaDay, Integer builderDay) {
        public static View from(NotificationPreference preference) {
            return new View(preference.version(), preference.enabled(), preference.offsets(),
                    TIME.format(preference.time()), preference.afterDue(), preference.weekly(),
                    preference.showAmounts(), preference.caixaDay(), preference.builderDay());
        }
    }
}
