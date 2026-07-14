package com.larkconnect.agent.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkerTest {
    @Test
    void schedulesPendingQueryWithoutMarkingTaskTerminal() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        QueryResumePublisher publisher = mock(QueryResumePublisher.class);
        when(checkpoints.claim(org.mockito.ArgumentMatchers.eq("r-pending"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(orchestrator.process("r-pending")).thenReturn(
                AgentOrchestrator.ProcessResult.pending(Duration.ofSeconds(4)));

        new AgentWorker(tasks, orchestrator, checkpoints, publisher)
                .handle(new AgentTaskMessage("r-pending"));

        verify(publisher).schedule("r-pending", Duration.ofSeconds(4));
        verify(tasks, never()).markSucceeded("r-pending");
        verify(tasks, never()).markPartial(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(tasks, never()).markFailed(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ignoresDuplicateRabbitDeliveryWhenCheckpointLeaseIsHeld() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        QueryResumePublisher publisher = mock(QueryResumePublisher.class);
        when(checkpoints.claim(org.mockito.ArgumentMatchers.eq("r-duplicate"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        new AgentWorker(tasks, orchestrator, checkpoints, publisher)
                .handle(new AgentTaskMessage("r-duplicate"));

        verify(orchestrator, never()).process("r-duplicate");
        verify(tasks, never()).markRunning("r-duplicate");
    }

    @Test
    void marksHandledAiOutageAsFailedWithoutThrowingForRetry() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.process("r1")).thenReturn(AgentOrchestrator.ProcessResult.failed(
                "飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）"));

        new AgentWorker(tasks, orchestrator).handle(new AgentTaskMessage("r1"));

        verify(tasks).markFailed("r1", "飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）");
        verify(tasks, never()).markSucceeded("r1");
    }
}
