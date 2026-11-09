package br.com.minhaobra.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class PaymentReminder {
    private PaymentReminder() {
    }

    public static LocalDate dueDate(YearMonth month, LocalDate specific, Integer recurringDay,
            PaymentMonth.Status status) {
        if (status != PaymentMonth.Status.OPEN) {
            return null;
        }
        if (specific != null) {
            return specific;
        }
        return recurringDay == null ? null : month.atDay(Math.min(recurringDay, month.lengthOfMonth()));
    }

    public static Instant scheduledAt(NotificationPreference preference, LocalDate due, LocalDate notice, ZoneId zone) {
        long daysAfterDue = ChronoUnit.DAYS.between(due, notice);
        boolean beforeOrOn = daysAfterDue <= 0 && daysAfterDue >= -30
                && preference.offsets().contains((int) -daysAfterDue);
        boolean nextDay = preference.afterDue() && daysAfterDue == 1;
        boolean weekly = preference.weekly() && daysAfterDue > 0 && daysAfterDue % 7 == 0;
        if (!preference.enabled() || !(beforeOrOn || nextDay || weekly)) {
            return null;
        }
        return notice.atTime(preference.time()).atZone(zone).toInstant();
    }
}
