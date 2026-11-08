package br.com.minhaobra.repository;

import br.com.minhaobra.domain.Account;
import br.com.minhaobra.dto.Accounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Account> MAPPER = AccountRepository::map;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Account map(ResultSet r, int row) throws SQLException {
        var pending = r.getTimestamp("totp_pending_until");
        return new Account(r.getObject("id", UUID.class), r.getString("username"), r.getString("display_name"),
                r.getString("email"), r.getString("password_hash"), r.getString("role"), r.getString("status"),
                r.getBoolean("must_change_password"),
                r.getLong("auth_version"), r.getLong("version"), r.getString("totp_secret"),
                r.getString("totp_pending_secret"),
                pending == null ? null : pending.toInstant(), r.getLong("totp_last_counter"),
                r.getTimestamp("created_at").toInstant());
    }

    public Optional<Account> byId(UUID id) {
        return first(jdbc.query("SELECT * FROM app_account WHERE id=?", MAPPER, id));
    }

    public Optional<Account> byIdForUpdate(UUID id) {
        return first(jdbc.query("SELECT * FROM app_account WHERE id=? FOR UPDATE", MAPPER, id));
    }

    public Optional<Account> byUsername(String username) {
        return first(jdbc.query("SELECT * FROM app_account WHERE username=?", MAPPER, username));
    }

    private static Optional<Account> first(List<Account> rows) {
        return rows.stream().findFirst();
    }

    public long adminCount() {
        return jdbc.queryForObject("SELECT count(*) FROM app_account WHERE role='ADMIN'", Long.class);
    }

    public void bootstrapLock() {
        jdbc.execute("SELECT pg_advisory_xact_lock(1680963451, 704221)");
    }

    public void requestLock(String scope) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", scope);
    }

    public Account insert(UUID id, String username, String displayName, String hash, String role) {
        jdbc.update(
                "INSERT INTO app_account(id,username,display_name,password_hash,role,status,must_change_password) VALUES (?,?,?,?,?,'ACTIVE',TRUE)",
                id, username, displayName, hash, role);
        return byId(id).orElseThrow();
    }

    public boolean insertRequest(UUID id, String username, String displayName, String email, String hash) {
        return jdbc.update(
                "INSERT INTO app_account(id,username,display_name,email,password_hash,role,status,must_change_password) VALUES (?,?,?,?,?,'USER','PENDING_APPROVAL',FALSE) ON CONFLICT (username) DO NOTHING",
                id, username, displayName, email, hash) == 1;
    }

    public Accounts.Page page(int page, int size, String search) {
        String predicate = "(?='' OR position(? in username)>0 OR position(? in lower(display_name))>0)";
        var items = jdbc
                .query("SELECT * FROM app_account WHERE " + predicate
                                + " ORDER BY (status='PENDING_APPROVAL') DESC,created_at DESC,id LIMIT ? OFFSET ?",
                        MAPPER, search, search, search, size, (long) page * size)
                .stream().map(Accounts.User::from).toList();
        long total = jdbc.queryForObject("SELECT count(*) FROM app_account WHERE " + predicate, Long.class, search,
                search, search);
        return new Accounts.Page(items, page, size, total);
    }

    public Account changePassword(UUID id, String hash) {
        jdbc.update(
                "UPDATE app_account SET password_hash=?,must_change_password=FALSE,auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                hash, id);
        return byId(id).orElseThrow();
    }

    public Account resetPassword(UUID id, String hash) {
        jdbc.update(
                "UPDATE app_account SET password_hash=?,must_change_password=TRUE,auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                hash, id);
        return byId(id).orElseThrow();
    }

    public Account status(UUID id, String status) {
        jdbc.update(
                "UPDATE app_account SET status=?,auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, id);
        return byId(id).orElseThrow();
    }

    public void revokeSessions(UUID id) {
        jdbc.update("DELETE FROM spring_session WHERE principal_name=?", id.toString());
    }

    public void pendingTotp(UUID id, String encrypted, Instant expiry) {
        jdbc.update("UPDATE app_account SET totp_pending_secret=?,totp_pending_until=? WHERE id=?", encrypted,
                java.sql.Timestamp.from(expiry), id);
    }

    public Account activateTotp(UUID id, String encrypted, long counter) {
        jdbc.update(
                "UPDATE app_account SET totp_secret=?,totp_pending_secret=NULL,totp_pending_until=NULL,totp_last_counter=?,auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                encrypted, counter, id);
        return byId(id).orElseThrow();
    }

    public boolean consumeCounter(UUID id, long expected, long next) {
        return jdbc.update("UPDATE app_account SET totp_last_counter=? WHERE id=? AND totp_last_counter=?", next, id,
                expected) == 1;
    }

    public void addRecovery(UUID id, String hash) {
        jdbc.update("INSERT INTO account_recovery_code(account_id,code_hash) VALUES (?,?)", id, hash);
    }

    public void clearRecovery(UUID id) {
        jdbc.update("DELETE FROM account_recovery_code WHERE account_id=?", id);
    }

    public boolean consumeRecovery(UUID id, String hash) {
        return jdbc.update("DELETE FROM account_recovery_code WHERE account_id=? AND code_hash=?", id, hash) == 1;
    }

    public Optional<CachedRequest> cached(UUID actor, String operation, UUID key) {
        return jdbc.query(
                "SELECT request_fingerprint,response_json FROM account_idempotency WHERE actor_id=? AND operation=? AND request_key=?",
                (r, n) -> new CachedRequest(r.getString(1), r.getString(2)), actor, operation, key).stream()
                .findFirst();
    }

    public Optional<CachedRequest> cachedRegistration(UUID key) {
        return jdbc.query(
                "SELECT request_fingerprint,response_json FROM account_idempotency WHERE operation='register' AND request_key=?",
                (r, n) -> new CachedRequest(r.getString(1), r.getString(2)), key).stream().findFirst();
    }

    public void saveCached(UUID actor, String operation, UUID key, String fingerprint, String response) {
        jdbc.update(
                "INSERT INTO account_idempotency(actor_id,operation,request_key,request_fingerprint,response_json) VALUES (?,?,?,?,?)",
                actor, operation, key, fingerprint, response);
    }

    public void event(UUID actor, UUID account, String type) {
        UUID id = UUID.randomUUID();
        String payload = "{\"id\":\"" + id + "\",\"accountId\":\"" + account + "\",\"actorId\":"
                + (actor == null ? "null" : "\"" + actor + "\"") + ",\"type\":\"" + type + "\"}";
        jdbc.update("INSERT INTO account_audit(id,actor_id,account_id,event_type) VALUES (?,?,?,?)", id, actor, account,
                type);
        jdbc.update("INSERT INTO account_outbox(id,event_key,event_type,payload) VALUES (?,?,?,?)", id, account, type,
                payload);
    }

    public record CachedRequest(String fingerprint, String response) {
    }
}
