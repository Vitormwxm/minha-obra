package br.com.minhaobra.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationDeviceRepository {
    private final JdbcTemplate jdbc;

    public NotificationDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void register(UUID installation, UUID owner, long authVersion, String token, String zone) {
        jdbc.update("""
                INSERT INTO notification_device(installation_id,account_id,auth_version,token,token_hash,zone_id)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (installation_id) DO UPDATE SET
                    account_id=EXCLUDED.account_id, auth_version=EXCLUDED.auth_version,
                    token=EXCLUDED.token, token_hash=EXCLUDED.token_hash, zone_id=EXCLUDED.zone_id,
                    registered_at=CASE
                        WHEN notification_device.account_id=EXCLUDED.account_id
                            AND notification_device.auth_version=EXCLUDED.auth_version
                        THEN notification_device.registered_at ELSE CURRENT_TIMESTAMP END,
                    refreshed_at=CURRENT_TIMESTAMP
                """, installation, owner, authVersion, token, tokenHash(token), zone);
        jdbc.update("""
                DELETE FROM notification_device WHERE account_id=? AND installation_id IN (
                    SELECT installation_id FROM notification_device WHERE account_id=?
                    ORDER BY (installation_id=?) DESC,refreshed_at DESC,installation_id OFFSET 10
                )
                """, owner, owner, installation);
    }

    public void revoke(UUID installation, UUID owner) {
        jdbc.update("DELETE FROM notification_device WHERE installation_id=? AND account_id=?", installation, owner);
    }

    private static byte[] tokenHash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 indisponível", unavailable);
        }
    }
}
