package br.com.minhaobra.repository;

import br.com.minhaobra.dto.Deletions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDeletionRepository {
    private final JdbcTemplate jdbc;

    public AccountDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Deletions.Request> pending(UUID accountId) {
        return jdbc.query("SELECT id,requested_at FROM account_deletion_request WHERE account_id=?",
                AccountDeletionRepository::request, accountId).stream().findFirst();
    }

    public Deletions.Request create(UUID accountId) {
        return jdbc.queryForObject("""
                INSERT INTO account_deletion_request(id,account_id) VALUES (?,?)
                RETURNING id,requested_at
                """, AccountDeletionRepository::request, UUID.randomUUID(), accountId);
    }

    public void cancel(UUID accountId, UUID requestId) {
        jdbc.update("DELETE FROM account_deletion_request WHERE account_id=? AND id=?", accountId, requestId);
    }

    public Optional<UUID> requestOwner(UUID requestId) {
        return jdbc.query("SELECT account_id FROM account_deletion_request WHERE id=?",
                (r, n) -> r.getObject("account_id", UUID.class), requestId).stream().findFirst();
    }

    public Deletions.Page page(int page, int size) {
        var items = jdbc.query("""
                SELECT r.id,r.requested_at,a.id AS account_id,a.username,a.display_name,a.email
                FROM account_deletion_request r JOIN app_account a ON a.id=r.account_id
                ORDER BY r.requested_at,r.id LIMIT ? OFFSET ?
                """, (r, n) -> new Deletions.AdminRequest(r.getObject("id", UUID.class),
                r.getTimestamp("requested_at").toInstant(), r.getObject("account_id", UUID.class),
                r.getString("username"), r.getString("display_name"), r.getString("email")),
                size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM account_deletion_request", Long.class);
        return new Deletions.Page(items, page, size, total);
    }

    public boolean purge(UUID accountId) {
        jdbc.update("""
                DELETE FROM account_outbox o USING account_audit a
                WHERE o.id=a.id AND (a.account_id=? OR a.actor_id=?)
                """, accountId, accountId);
        jdbc.update("DELETE FROM account_audit WHERE account_id=? OR actor_id=?", accountId, accountId);
        // Administrative operations cache the target's account snapshot under the administrator's ID.
        jdbc.update("DELETE FROM account_idempotency WHERE operation IN (?,?,?,?)",
                "approve:" + accountId, "block:" + accountId, "unblock:" + accountId, "reset:" + accountId);
        return jdbc.update("DELETE FROM app_account WHERE id=? AND role='USER'", accountId) == 1;
    }

    private static Deletions.Request request(ResultSet row, int position) throws SQLException {
        return new Deletions.Request(row.getObject("id", UUID.class), row.getTimestamp("requested_at").toInstant());
    }
}
