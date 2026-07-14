package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class QueryCheckpointRepository {
    private final JdbcTemplate jdbc;
    private final long progressNoticeMs;
    private final long hardTimeoutMs;

    @Autowired
    public QueryCheckpointRepository(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.progressNoticeMs = properties.agent().queryProgressNoticeMs();
        this.hardTimeoutMs = properties.agent().queryHardTimeoutMs();
    }

    QueryCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.progressNoticeMs = 900_000;
        this.hardTimeoutMs = 1_800_000;
    }

    public QuerySession initialize(String requestId, Instant createdAt) {
        Instant notice = createdAt.plusMillis(progressNoticeMs);
        Instant deadline = createdAt.plusMillis(hardTimeoutMs);
        jdbc.update("""
                insert into agent_query_checkpoint(request_id, phase, version, state_json, next_run_at,
                  heartbeat_at, notice_at, deadline_at, recovered_after_restart, created_at, updated_at)
                values (?, ?, 0, '{}', ?, ?, ?, ?, false, ?, ?)
                on duplicate key update request_id=request_id
                """, requestId, QueryPhase.PLANNING.name(), null, ts(createdAt), ts(notice), ts(deadline),
                ts(createdAt), ts(createdAt));
        return load(requestId).orElseThrow();
    }

    public Optional<QuerySession> load(String requestId) {
        List<QuerySession> rows = jdbc.query("select * from agent_query_checkpoint where request_id=?",
                (rs, row) -> map(rs), requestId);
        return rows.stream().findFirst();
    }

    public boolean advance(String requestId, long expectedVersion, QueryPhase phase, String stateJson, Instant nextRunAt) {
        return jdbc.update("""
                update agent_query_checkpoint set phase=?, version=version+1, state_json=?, next_run_at=?,
                  heartbeat_at=?, updated_at=? where request_id=? and version=?
                """, phase.name(), stateJson, ts(nextRunAt), ts(Instant.now()), ts(Instant.now()),
                requestId, expectedVersion) == 1;
    }

    public boolean saveProgress(String requestId, QueryPhase phase, String stateJson) {
        Optional<QuerySession> session = load(requestId);
        return session.isPresent() && advance(requestId, session.get().version(), phase, stateJson, null);
    }

    public boolean claim(String requestId, Instant now, Duration lease) {
        Instant leaseUntil = now.plus(lease);
        return jdbc.update("""
                update agent_query_checkpoint set version=version+1, next_run_at=?, heartbeat_at=?, updated_at=?
                where request_id=? and phase not in ('COMPLETED','PARTIAL','FAILED')
                  and (next_run_at is null or next_run_at <= ?)
                """, ts(leaseUntil), ts(now), ts(now), requestId, ts(now)) == 1;
    }

    public List<QuerySession> findRecoverable(Instant dueAt, Instant staleBefore) {
        return jdbc.query("""
                select * from agent_query_checkpoint where phase not in ('COMPLETED','PARTIAL','FAILED')
                  and ((next_run_at is not null and next_run_at <= ?) or heartbeat_at <= ?)
                """, (rs, row) -> map(rs), ts(dueAt), ts(staleBefore));
    }

    public void markRecovered(String requestId) {
        jdbc.update("update agent_query_checkpoint set recovered_after_restart=true where request_id=?", requestId);
    }

    public void heartbeatRunningTasks() {
        jdbc.update("""
                update agent_query_checkpoint q join agent_task t on t.request_id=q.request_id
                  set q.heartbeat_at=current_timestamp(3), q.updated_at=current_timestamp(3),
                      q.next_run_at=case when q.phase='POLLING_ASYNC' then q.next_run_at
                        else timestampadd(second, 60, current_timestamp(3)) end,
                      t.heartbeat_at=current_timestamp(3)
                where t.status='RUNNING' and q.phase not in ('COMPLETED','PARTIAL','FAILED')
                """);
    }

    public List<QuerySession> findNoticeDue(Instant now) {
        return jdbc.query("""
                select * from agent_query_checkpoint where notice_at <= ?
                  and phase not in ('COMPLETED','PARTIAL','FAILED')
                """, (rs, row) -> map(rs), ts(now));
    }

    private QuerySession map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QuerySession(rs.getString("request_id"), QueryPhase.valueOf(rs.getString("phase")),
                rs.getLong("version"), rs.getString("state_json"), instant(rs.getTimestamp("next_run_at")),
                instant(rs.getTimestamp("heartbeat_at")), instant(rs.getTimestamp("notice_at")),
                instant(rs.getTimestamp("deadline_at")), rs.getBoolean("recovered_after_restart"));
    }

    private Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
