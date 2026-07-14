package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.mcp.McpQueryGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDataChunkerTest {
    @Test
    void sendsEveryBusinessRecordAndRemovesInternalFields() {
        List<Map<String, Object>> records = java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> Map.<String, Object>of(
                        "dataId", "internal-" + index,
                        "name", "作业票-" + index,
                        "description", "业务数据".repeat(30),
                        "webUrl", "https://example.test/" + index))
                .toList();
        McpQueryGateway.ToolObservation observation = new McpQueryGateway.ToolObservation(
                "P1", "罗诊", "query_form_data_list", "SUCCEEDED",
                Map.of("records", records, "coverageComplete", true), null, 1, 1, false);

        BusinessDataChunker.ChunkedData result = new BusinessDataChunker(new ObjectMapper(), 4_000)
                .chunk(List.of(observation));

        assertThat(result.recordCount()).isEqualTo(25);
        assertThat(result.chunks()).hasSizeGreaterThan(1);
        assertThat(result.chunks()).allMatch(chunk -> chunk.chars() < 4_500);
        String all = result.chunks().stream().map(BusinessDataChunker.DataChunk::json)
                .reduce("", String::concat);
        assertThat(all).doesNotContain("dataId").doesNotContain("internal-")
                .contains("作业票-0").contains("作业票-24").contains("webUrl");
        assertThat(result.chunks().stream().flatMap(chunk -> chunk.recordFingerprints().stream()).distinct())
                .hasSize(25);
    }

    @Test
    void fragmentsOversizedFieldWithRecordKeyAndReassemblableSequence() throws Exception {
        String text = "超长字段".repeat(3_000);
        ObjectMapper mapper = new ObjectMapper();
        McpQueryGateway.ToolObservation observation = new McpQueryGateway.ToolObservation(
                "P1", "罗诊", "query_form_data_list", "SUCCEEDED",
                Map.of("records", List.of(Map.of("name", "大记录", "detail", text))), null, 1, 1, false);

        BusinessDataChunker.ChunkedData result = new BusinessDataChunker(mapper, 4_000)
                .chunk(List.of(observation));

        assertThat(result.chunks()).hasSizeGreaterThan(1);
        List<Map<String, Object>> detailFragments = new java.util.ArrayList<>();
        for (BusinessDataChunker.DataChunk chunk : result.chunks()) {
            List<Map<String, Object>> values = mapper.convertValue(mapper.readTree(chunk.json()).get("records"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            values.stream().filter(value -> "$.detail".equals(value.get("fieldPath")))
                    .forEach(detailFragments::add);
        }
        assertThat(detailFragments).hasSizeGreaterThan(1)
                .extracting(value -> value.get("recordFingerprint")).doesNotContainNull();
        assertThat(detailFragments).extracting(value -> value.get("fieldFragmentIndex"))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, detailFragments.size())
                        .boxed().toList());
        assertThat(detailFragments.stream().map(value -> String.valueOf(value.get("fieldValueFragment")))
                .reduce("", String::concat)).isEqualTo(text);
    }
}
