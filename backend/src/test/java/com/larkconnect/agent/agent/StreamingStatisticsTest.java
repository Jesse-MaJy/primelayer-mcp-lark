package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingStatisticsTest {
    @Test
    void restoresCheckpointStateAndContinuesExactAggregation() {
        ObjectMapper mapper = new ObjectMapper();
        StreamingStatistics first = new StreamingStatistics(mapper);
        first.accept(List.of(Map.of("id", "r-1", "category", "A", "amount", 10,
                "date", "2026-07-13")), 2);

        StreamingStatistics restored = StreamingStatistics.restore(mapper, first.state());
        restored.accept(List.of(Map.of("id", "r-2", "category", "B", "amount", 30,
                "date", "2026-07-14")), 2);

        Map<String, Object> snapshot = restored.snapshot();
        assertThat(snapshot).containsEntry("fetchedCount", 2L).containsEntry("coverageComplete", true);
        assertThat(snapshot.toString()).contains("sum=40").contains("average=20").contains("earliest=2026-07-13");
    }

    @Test
    void aggregatesLargeInputsWithoutRetainingRecordsAndMarksHighCardinalityApproximate() {
        StreamingStatistics statistics = new StreamingStatistics(new ObjectMapper());
        List<Map<String, Object>> page = new ArrayList<>();
        for (int i = 0; i < 1_100; i++) {
            page.add(Map.of("id", "r-" + i, "category", "c-" + i, "amount", i,
                    "date", "2026-07-14"));
        }

        statistics.accept(page, 2_200);
        statistics.accept(page, 2_200);
        Map<String, Object> snapshot = statistics.snapshot();

        assertThat(snapshot).containsEntry("fetchedCount", 2_200L)
                .containsEntry("reportedTotalCount", 2_200L)
                .containsEntry("coverageComplete", true);
        assertThat(statistics.retainedRecordCount()).isLessThanOrEqualTo(3);
        assertThat(snapshot.toString()).contains("approximate=true").contains("topCategories");
    }
}
