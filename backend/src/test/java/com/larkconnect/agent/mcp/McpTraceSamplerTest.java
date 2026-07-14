package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpTraceSamplerTest {
    @Test
    void keepsMetricsHashAndAtMostThreeSanitizedRecords() {
        McpTraceSampler sampler = new McpTraceSampler(new ObjectMapper());
        Map<String, Object> sampled = sampler.sample(Map.of("result", Map.of(
                "total", 4,
                "items", List.of(
                        Map.of("id", 1, "secret", "x".repeat(500)), Map.of("id", 2),
                        Map.of("id", 3), Map.of("id", 4)))));

        assertThat(sampled).containsEntry("returnedCount", 4).containsEntry("reportedTotalCount", 4);
        assertThat(sampled.get("contentHash")).isNotNull();
        assertThat((List<?>) sampled.get("samples")).hasSize(3);
        assertThat(sampled.toString()).doesNotContain("x".repeat(100));
    }
}
