package br.com.minhaobra.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountOutboxRepository {
    private final JdbcTemplate jdbc;
    public AccountOutboxRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Event> pending() {
        return jdbc.query("SELECT id,event_key,payload,attempts FROM account_outbox WHERE published_at IS NULL AND available_at<=CURRENT_TIMESTAMP ORDER BY occurred_at LIMIT 10 FOR UPDATE SKIP LOCKED",
            (r,n)->new Event(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getInt(4)));
    }
    public void published(UUID id) {jdbc.update("UPDATE account_outbox SET published_at=CURRENT_TIMESTAMP WHERE id=?",id);}
    public void retry(UUID id,int attempts,Instant next) {jdbc.update("UPDATE account_outbox SET attempts=?,available_at=? WHERE id=?",attempts,java.sql.Timestamp.from(next),id);}
    public record Event(UUID id,UUID key,String payload,int attempts) {}
}
