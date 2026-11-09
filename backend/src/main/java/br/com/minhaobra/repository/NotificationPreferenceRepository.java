package br.com.minhaobra.repository;

import br.com.minhaobra.domain.NotificationPreference;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationPreferenceRepository {
    private final JdbcTemplate jdbc;

    public NotificationPreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NotificationPreference> byOwner(UUID owner) {
        return jdbc.query("""
                SELECT version, enabled, offsets, reminder_time, after_due, weekly, show_amounts, caixa_day, builder_day
                FROM notification_preference WHERE account_id=?
                """, NotificationPreferenceRepository::map, owner).stream().findFirst();
    }

    public void save(UUID owner, NotificationPreference preference) {
        Short[] offsets = preference.offsets().stream().map(Integer::shortValue).toArray(Short[]::new);
        jdbc.update("""
                INSERT INTO notification_preference(account_id,version,enabled,offsets,reminder_time,
                    after_due,weekly,show_amounts,caixa_day,builder_day) VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (account_id) DO UPDATE SET version=EXCLUDED.version, enabled=EXCLUDED.enabled,
                    enabled_since=CASE WHEN EXCLUDED.enabled AND NOT notification_preference.enabled
                        THEN CURRENT_TIMESTAMP ELSE notification_preference.enabled_since END,
                    offsets=EXCLUDED.offsets, reminder_time=EXCLUDED.reminder_time, after_due=EXCLUDED.after_due,
                    weekly=EXCLUDED.weekly, show_amounts=EXCLUDED.show_amounts,
                    caixa_day=EXCLUDED.caixa_day, builder_day=EXCLUDED.builder_day
                """, owner, preference.version(), preference.enabled(), new SqlArrayValue("smallint", (Object[]) offsets),
                preference.time(), preference.afterDue(), preference.weekly(), preference.showAmounts(),
                preference.caixaDay(), preference.builderDay());
    }

    private static NotificationPreference map(ResultSet row, int position) throws SQLException {
        Array sqlOffsets = row.getArray("offsets");
        List<Integer> offsets;
        try {
            offsets = Arrays.stream((Object[]) sqlOffsets.getArray())
                    .map(value -> ((Number) value).intValue()).toList();
        } finally {
            sqlOffsets.free();
        }
        return new NotificationPreference(row.getLong("version"), row.getBoolean("enabled"), offsets,
                row.getObject("reminder_time", LocalTime.class), row.getBoolean("after_due"), row.getBoolean("weekly"),
                row.getBoolean("show_amounts"), row.getObject("caixa_day", Integer.class),
                row.getObject("builder_day", Integer.class));
    }
}
