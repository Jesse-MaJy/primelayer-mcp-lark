package com.larkconnect.agent.agent;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.RabbitConfig;
import com.larkconnect.agent.feishu.FeishuIncomingMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;

@Service
public class AgentTaskService {
    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;

    public AgentTaskService(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void createAndPublish(FeishuIncomingMessage message) {
        String requestId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("""
                    insert into agent_task(request_id, feishu_message_id, feishu_open_id, feishu_chat_id, chat_type, message_text, status)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, requestId, message.messageId(), message.openId(), message.chatId(), message.chatType(), message.text(), Status.PENDING);
        } catch (DuplicateKeyException ignored) {
            return;
        }
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.TASK_ROUTING_KEY, new AgentTaskMessage(requestId));
    }

    public Map<String, Object> loadTask(String requestId) {
        return jdbcTemplate.queryForMap("select * from agent_task where request_id = ?", requestId);
    }

    public Instant createdAt(String requestId) {
        java.sql.Timestamp value = jdbcTemplate.queryForObject(
                "select created_at from agent_task where request_id = ?", java.sql.Timestamp.class, requestId);
        return value == null ? Instant.now() : value.toInstant();
    }

    public boolean markRunning(String requestId) {
        return jdbcTemplate.update("update agent_task set status = ?, phase = ?, started_at = coalesce(started_at,current_timestamp), heartbeat_at=current_timestamp where request_id = ? and status in (?, ?)",
                Status.RUNNING, QueryPhase.PLANNING.name(), requestId, Status.PENDING, Status.RUNNING) == 1;
    }

    public void markSucceeded(String requestId) {
        jdbcTemplate.update("update agent_task set status = ?, phase = ?, finished_at = current_timestamp where request_id = ? and status = ?",
                Status.SUCCEEDED, QueryPhase.COMPLETED.name(), requestId, Status.RUNNING);
    }

    public void markFailed(String requestId, String error) {
        jdbcTemplate.update("update agent_task set status = ?, phase = ?, error_message = ?, finished_at = current_timestamp where request_id = ? and status in (?, ?)",
                Status.FAILED, QueryPhase.FAILED.name(), error, requestId, Status.PENDING, Status.RUNNING);
    }

    public void markPartial(String requestId, String reason) {
        jdbcTemplate.update("update agent_task set status=?, phase=?, error_message=?, finished_at=current_timestamp where request_id=? and status=?",
                Status.PARTIAL, QueryPhase.PARTIAL.name(), reason, requestId, Status.RUNNING);
    }

    public boolean isCancelled(String requestId) {
        String status = jdbcTemplate.queryForObject(
                "select status from agent_task where request_id=?", String.class, requestId);
        return Status.CANCELLED.equals(status);
    }
}
