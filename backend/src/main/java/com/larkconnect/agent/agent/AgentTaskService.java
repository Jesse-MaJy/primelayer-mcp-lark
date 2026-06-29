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

    public void markRunning(String requestId) {
        jdbcTemplate.update("update agent_task set status = ?, started_at = current_timestamp where request_id = ?", Status.RUNNING, requestId);
    }

    public void markSucceeded(String requestId) {
        jdbcTemplate.update("update agent_task set status = ?, finished_at = current_timestamp where request_id = ?", Status.SUCCEEDED, requestId);
    }

    public void markFailed(String requestId, String error) {
        jdbcTemplate.update("update agent_task set status = ?, error_message = ?, finished_at = current_timestamp where request_id = ?", Status.FAILED, error, requestId);
    }
}
