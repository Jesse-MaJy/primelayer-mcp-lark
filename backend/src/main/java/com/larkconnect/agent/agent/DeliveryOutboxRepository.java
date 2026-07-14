package com.larkconnect.agent.agent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DeliveryOutboxRepository {
    private final JdbcTemplate jdbc;
    public DeliveryOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean enqueueOnce(String requestId, DeliveryType type, String payloadJson, Instant now) {
        try {
            return jdbc.update("""
                    insert into agent_delivery_outbox(request_id, delivery_type, payload_json, status, attempts, next_attempt_at)
                    values (?, ?, ?, 'PENDING', 0, ?)
                    """, requestId, type.name(), payloadJson, ts(now)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public Optional<DeliveryOutboxEntry> claimDue(Instant now, Instant leaseUntil) {
        List<Long> ids = jdbc.query("""
                select id from agent_delivery_outbox where status in ('PENDING','RETRY','SENDING')
                  and next_attempt_at <= ? and (lease_until is null or lease_until < ?) order by id limit 1
                """, (rs, row) -> rs.getLong(1), ts(now), ts(now));
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        if (jdbc.update("""
                update agent_delivery_outbox set status='SENDING', attempts=attempts+1, lease_until=?
                where id=? and status in ('PENDING','RETRY','SENDING') and (lease_until is null or lease_until < ?)
                """, ts(leaseUntil), id, ts(now)) != 1) return Optional.empty();
        return load(id);
    }

    public void markSent(long id, String messageId, Instant sentAt) {
        jdbc.update("update agent_delivery_outbox set status='SENT', sent_at=?, feishu_message_id=?, lease_until=null where id=?",
                ts(sentAt), messageId, id);
    }

    public void markRetry(long id, String error, Instant nextAttemptAt) {
        jdbc.update("update agent_delivery_outbox set status='RETRY', last_error=?, next_attempt_at=?, lease_until=null where id=?",
                error, ts(nextAttemptAt), id);
    }

    private Optional<DeliveryOutboxEntry> load(long id) {
        return jdbc.query("select * from agent_delivery_outbox where id=?", (rs, row) -> new DeliveryOutboxEntry(
                rs.getLong("id"), rs.getString("request_id"), DeliveryType.valueOf(rs.getString("delivery_type")),
                rs.getString("payload_json"), rs.getString("status"), rs.getInt("attempts"),
                instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("lease_until")),
                rs.getString("feishu_message_id"), rs.getString("last_error")), id).stream().findFirst();
    }
    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
