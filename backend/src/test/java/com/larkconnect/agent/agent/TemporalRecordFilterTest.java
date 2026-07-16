package com.larkconnect.agent.agent;

import com.larkconnect.agent.mcp.McpQueryGateway;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalRecordFilterTest {
    @Test
    void removesOutOfRangeAndMissingDateRecords() {
        TemporalRange range = new TemporalRange(LocalDateTime.parse("2026-06-01T00:00:00"),
                LocalDateTime.parse("2026-06-30T23:59:59"), "createTime", "上个月", ZoneId.of("Asia/Shanghai"));
        McpQueryGateway.ToolObservation observation = new McpQueryGateway.ToolObservation(
                "P1", "罗诊", "query_form_data_list", "SUCCEEDED", Map.of(
                "records", List.of(
                        Map.of("name", "A", "createTime", "2026-06-05 12:00:00"),
                        Map.of("name", "B", "createTime", "2026-05-31 23:59:59"),
                        Map.of("name", "C")),
                "coverageComplete", true), null, 1, 1, false);

        McpQueryGateway.ToolObservation filtered = new TemporalRecordFilter().apply(List.of(observation), range).get(0);

        assertThat((List<?>) filtered.payload().get("records")).hasSize(1);
        assertThat(filtered.payload()).containsEntry("coverageComplete", false);
        assertThat(filtered.payload().get("incompleteReason").toString())
                .contains("范围外记录").contains("缺少 createTime");
    }
}
