package br.com.minhaobra.domain;

import java.time.LocalTime;
import java.util.List;

public record NotificationPreference(long version, boolean enabled, List<Integer> offsets, LocalTime time,
        boolean afterDue, boolean weekly, boolean showAmounts, Integer caixaDay, Integer builderDay) {
    public NotificationPreference {
        offsets = List.copyOf(offsets);
    }

    public static NotificationPreference defaults() {
        return new NotificationPreference(0, false, List.of(3, 1, 0), LocalTime.of(9, 0), true, false, true,
                null, null);
    }
}
