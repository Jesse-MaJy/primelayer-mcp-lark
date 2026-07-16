package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongRunningNoticeSchedulerTest {
    @Test
    void createsOnlyTheIdempotentNoticeOutboxEntryForDueSession() {
        Instant now = Instant.parse("2026-07-14T00:15:00Z");
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        DeliveryOutboxRepository outbox = mock(DeliveryOutboxRepository.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        QuerySession session = new QuerySession("r1", QueryPhase.POLLING_ASYNC, 2, "{}", null,
                now, now, now.plusSeconds(900), false);
        when(checkpoints.findNoticeDue(now)).thenReturn(List.of(session));
        when(tasks.loadTask("r1")).thenReturn(Map.of("feishu_message_id", "m1", "message_text", "分析项目"));

        new LongRunningNoticeScheduler(checkpoints, outbox, tasks, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC)).enqueueDueNotices();

        verify(outbox).enqueueOnce(eq("r1"), eq(DeliveryType.LONG_RUNNING_NOTICE),
                contains("最长运行至 30 分钟"), eq(now));
    }
}
