package com.larkconnect.agent.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainTraceSummaryTest {
    @Test
    void exposesCacheHitsAndSavedMcpCalls() {
        ChainTrace trace = new ChainTrace("r-cache");
        trace.summary.cacheHits = 2;
        trace.summary.savedMcpCalls = 2;

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.toMap().get("summary");

        assertThat(summary).containsEntry("cacheHits", 2).containsEntry("savedMcpCalls", 2);
    }
}
