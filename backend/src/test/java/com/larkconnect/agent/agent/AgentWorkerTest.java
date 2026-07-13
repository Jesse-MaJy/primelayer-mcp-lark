package com.larkconnect.agent.agent;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkerTest {
    @Test
    void marksHandledAiOutageAsFailedWithoutThrowingForRetry() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.process("r1")).thenReturn(false);

        new AgentWorker(tasks, orchestrator).handle(new AgentTaskMessage("r1"));

        verify(tasks).markFailed("r1", "AI 服务暂不可用");
        verify(tasks, never()).markSucceeded("r1");
    }
}
