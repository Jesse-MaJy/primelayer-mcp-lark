package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LongRunningNoticeScheduler {
    private final QueryCheckpointRepository checkpoints;
    private final DeliveryOutboxRepository outbox;
    private final AgentTaskService tasks;
    private final ObjectMapper mapper;
    private final Clock clock;

    public LongRunningNoticeScheduler(QueryCheckpointRepository checkpoints, DeliveryOutboxRepository outbox,
                                      AgentTaskService tasks, ObjectMapper mapper, Clock clock) {
        this.checkpoints = checkpoints; this.outbox = outbox; this.tasks = tasks; this.mapper = mapper; this.clock = clock;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void enqueueDueNotices() {
        Instant now = clock.instant();
        for (QuerySession session : checkpoints.findNoticeDue(now)) {
            try {
                Map<String, Object> task = tasks.loadTask(session.requestId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("messageId", String.valueOf(task.get("feishu_message_id")));
                payload.put("question", String.valueOf(task.get("message_text")));
                payload.put("answer", noticeText(session));
                payload.put("title", "项目数据仍在查询");
                payload.put("template", "orange");
                outbox.enqueueOnce(session.requestId(), DeliveryType.LONG_RUNNING_NOTICE,
                        mapper.writeValueAsString(payload), now);
            } catch (Exception ignored) {
                // 下次调度继续尝试；唯一键保证成功后不会重复创建。
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String noticeText(QuerySession session) {
        long physicalCalls = 0;
        long fetched = 0;
        long total = 0;
        int pollAttempts = 0;
        try {
            Map<String, Object> state = mapper.readValue(session.stateJson(), Map.class);
            physicalCalls = number(state.get("physicalMcpCalls"));
            if (state.get("pendingAsync") instanceof Map<?, ?> pending) {
                pollAttempts = (int) number(pending.get("pollAttempts"));
                if (pending.get("input") instanceof Map<?, ?> input
                        && input.get("arguments") instanceof Map<?, ?> arguments
                        && arguments.get("_paginationStates") instanceof Map<?, ?> projects) {
                    for (Object value : projects.values()) {
                        if (!(value instanceof Map<?, ?> project)) continue;
                        Object continuationValue = project.get("continuation");
                        Object payloadValue = project.get("payload");
                        Map<?, ?> source = continuationValue instanceof Map<?, ?> continuation
                                && continuation.get("statisticsState") instanceof Map<?, ?> statistics
                                ? statistics : payloadValue instanceof Map<?, ?> completed ? completed : Map.of();
                        fetched += number(source.get("fetchedCount"));
                        total += number(source.get("reportedTotalCount"));
                    }
                }
            }
        } catch (Exception ignored) {
            // 保留基础通知，不让统计解析影响投递。
        }
        String coverage = total <= 0 ? "覆盖率暂未知"
                : "已获取 " + fetched + "/" + total + "，覆盖率 "
                + String.format(java.util.Locale.ROOT, "%.1f%%", Math.min(100d, fetched * 100d / total));
        return "查询仍在后台继续，无需重复提问，最长运行至 30 分钟。"
                + "\n当前阶段：" + session.phase() + "；物理 MCP 调用：" + physicalCalls
                + "；轮询次数：" + pollAttempts + "；" + coverage + "。";
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
