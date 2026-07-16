package com.larkconnect.agent.agent;

import com.larkconnect.agent.common.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskCancellationGuard {
    private final JdbcTemplate jdbc;

    public TaskCancellationGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isCancelled(String requestId) {
        if (requestId == null || requestId.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.query(
                "select status from agent_task where request_id=?",
                rs -> rs.next() && Status.CANCELLED.equals(rs.getString(1)), requestId));
    }

    public void check(String requestId) {
        if (isCancelled(requestId)) throw new QueryCancelledException(requestId);
    }
}
