package com.larkconnect.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskStateTest {
    @Test
    void recognizesNestedPendingAndTerminalStates() {
        assertThat(AsyncTaskState.from(Map.of("data", Map.of("task_status", "processing"))).waiting()).isTrue();
        assertThat(AsyncTaskState.from(Map.of("result", Map.of("state", "COMPLETED"))).successful()).isTrue();
        assertThat(AsyncTaskState.from(Map.of("status", "FAILED")).terminal()).isTrue();
        assertThat(AsyncTaskState.from(Map.of("message", "ok")).known()).isFalse();
    }
}
