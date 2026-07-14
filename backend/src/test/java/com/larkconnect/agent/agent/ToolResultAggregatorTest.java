package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.mcp.McpQueryGateway.ToolObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultAggregatorTest {

    @Test
    void aggregatesEveryRecordIntoCoverageStatisticsIdentifiersAndEvidence() {
        List<Object> records = new ArrayList<>();
        for (int i = 1; i <= 3439; i++) {
            records.add(new LinkedHashMap<>(Map.of(
                    "dataId", "D-" + i,
                    "status", i <= 3000 ? "Closed" : "Open",
                    "score", i,
                    "createdAt", i <= 2000 ? "2026-07-08T10:00:00" : "2026-07-13T10:00:00"
            )));
        }
        ToolObservation observation = observation(records, 3439, 3439, true, null);

        Map<String, Object> result = new ToolResultAggregator(new ObjectMapper()).aggregate(List.of(observation));

        assertThat(result).containsKeys("coverage", "statistics", "identifiers", "evidenceSamples", "dataGaps");
        Map<String, Object> coverage = map(((List<?>) result.get("coverage")).get(0));
        assertThat(coverage).containsEntry("fetchedCount", 3439).containsEntry("reportedTotalCount", 3439)
                .containsEntry("coverageComplete", true);
        Map<String, Object> statistics = map(result.get("statistics"));
        assertThat(statistics).containsEntry("recordCount", 3439).containsEntry("uniqueRecordCount", 3439);
        Map<String, Object> fields = map(statistics.get("fields"));
        assertThat(map(fields.get("status"))).containsEntry("Closed", 3000).containsEntry("Open", 439);
        assertThat(map(fields.get("score"))).containsEntry("min", 1).containsEntry("max", 3439);
        assertThat(map(statistics.get("dateRange"))).containsEntry("earliest", "2026-07-08")
                .containsEntry("latest", "2026-07-13");
        Map<String, Object> identifiers = map(result.get("identifiers"));
        assertThat((List<?>) identifiers.get("dataId")).hasSize(200);
        assertThat(identifiers).containsEntry("dataIdOmittedCount", 3239);
        assertThat((List<?>) result.get("evidenceSamples")).hasSize(3);
    }

    @Test
    void reportsIncompleteCoverageAsDataGapWithoutDroppingFetchedRecords() {
        ToolObservation observation = observation(List.of(Map.of("id", "1"), Map.of("id", "2")),
                2, 5, false, "empty_page_before_total");

        Map<String, Object> result = new ToolResultAggregator(new ObjectMapper()).aggregate(List.of(observation));

        assertThat(map(result.get("statistics"))).containsEntry("recordCount", 2);
        assertThat((List<?>) result.get("dataGaps")).anySatisfy(gap ->
                assertThat(String.valueOf(gap)).contains("empty_page_before_total").contains("2/5"));
    }

    private ToolObservation observation(List<Object> records, int fetched, int total,
                                        boolean complete, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", records);
        payload.put("fetchedCount", fetched);
        payload.put("reportedTotalCount", total);
        payload.put("coverageComplete", complete);
        payload.put("incompleteReason", reason);
        payload.put("pageCount", 35);
        return new ToolObservation("P1", "项目一", "query_form_data_list", complete ? "SUCCEEDED" : "PARTIAL",
                payload, null, 35, 35, false, fetched, total, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
