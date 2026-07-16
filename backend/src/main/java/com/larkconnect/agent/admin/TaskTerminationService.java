package com.larkconnect.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.DeliveryOutboxRepository;
import com.larkconnect.agent.agent.DeliveryType;
import com.larkconnect.agent.agent.QueryPhase;
import com.larkconnect.agent.common.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskTerminationService {
    private final JdbcTemplate jdbc;
    private final DeliveryOutboxRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TaskTerminationService(JdbcTemplate jdbc, DeliveryOutboxRepository outbox,
                                  ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public TerminateTaskResponse terminate(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select request_id, status, feishu_message_id, message_text from agent_task where request_id=? for update",
                requestId);
        if (rows.isEmpty()) throw new IllegalArgumentException("任务不存在：" + requestId);
        Map<String, Object> task = rows.get(0);
        String status = String.valueOf(task.get("status"));
        if (Status.CANCELLED.equals(status)) return new TerminateTaskResponse(requestId, Status.CANCELLED);
        if (!Status.PENDING.equals(status) && !Status.RUNNING.equals(status)) {
            throw new IllegalArgumentException("仅待处理或运行中的任务可以终止，当前状态：" + status);
        }
        if (outbox.finalResultSendingOrSent(requestId)) {
            throw new IllegalArgumentException("最终答案正在发送或已经发送，无法终止");
        }

        int updated = jdbc.update("""
                update agent_task set status=?, phase=?, error_message=?, finished_at=current_timestamp
                where request_id=? and status in (?, ?)
                """, Status.CANCELLED, QueryPhase.CANCELLED.name(), "任务已由管理员终止",
                requestId, Status.PENDING, Status.RUNNING);
        if (updated != 1) throw new IllegalArgumentException("任务状态已变化，请刷新后重试");

        jdbc.update("""
                update agent_query_checkpoint set phase=?, next_run_at=null,
                  heartbeat_at=current_timestamp(3), updated_at=current_timestamp(3), version=version+1
                where request_id=? and phase not in ('COMPLETED','PARTIAL','FAILED','CANCELLED')
                """, QueryPhase.CANCELLED.name(), requestId);
        outbox.cancelUnsentOutputs(requestId);

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", String.valueOf(task.get("feishu_message_id")));
            payload.put("question", String.valueOf(task.get("message_text")));
            payload.put("answer", "本次任务已由管理员终止。如仍需查询，请重新发送问题。");
            payload.put("title", "任务已终止");
            payload.put("template", "orange");
            outbox.enqueueOnce(requestId, DeliveryType.TASK_CANCELLED,
                    mapper.writeValueAsString(payload), clock.instant());
        } catch (Exception e) {
            throw new IllegalStateException("终止通知创建失败", e);
        }
        return new TerminateTaskResponse(requestId, Status.CANCELLED);
    }

    public record TerminateTaskResponse(String requestId, String status) {}
}
