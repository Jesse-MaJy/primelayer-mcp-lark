package com.larkconnect.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class McpToolRegistryTest {
    private final McpToolRegistry registry = new McpToolRegistry();
    private final List<Map<String, Object>> tools = List.of(Map.of(
            "name", "query_tasks",
            "description", "query",
            "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("status", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                    "required", List.of("status"),
                    "additionalProperties", false
            )
    ));

    @Test
    void rejectsMissingRequiredArgument() {
        assertThatThrownBy(() -> registry.validate("query_tasks", Map.of(), tools))
                .hasMessageContaining("缺少必填参数");
    }

    @Test
    void rejectsUnknownArgumentWhenSchemaIsClosed() {
        assertThatThrownBy(() -> registry.validate("query_tasks", Map.of("status", "OPEN", "invented", true), tools))
                .hasMessageContaining("未定义参数");
    }

    @Test
    void rejectsWrongArgumentType() {
        assertThatThrownBy(() -> registry.validate("query_tasks", Map.of("status", "OPEN", "limit", "many"), tools))
                .hasMessageContaining("类型应为 integer");
    }

    @Test
    void rejectsToolExplicitlyMarkedAsMutatingEvenWhenNameLooksReadOnly() {
        Map<String, Object> tool = Map.of(
                "name", "query_and_update_tasks",
                "inputSchema", Map.of("type", "object"),
                "annotations", Map.of("readOnlyHint", false, "destructiveHint", true));

        assertThat(registry.filterDiscoveredTools(List.of(tool))).isEmpty();
    }

    @Test
    void rejectsDisguisedMutatingToolWithoutAnnotations() {
        Map<String, Object> tool = Map.of(
                "name", "query_and_update_tasks",
                "inputSchema", Map.of("type", "object"));

        assertThat(registry.filterDiscoveredTools(List.of(tool))).isEmpty();
    }

    @Test
    void validatesNestedObjectsArraysAndEnumsFromOriginalSchema() {
        List<Map<String, Object>> nestedTools = List.of(Map.of(
                "name", "query_tasks",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("filters", Map.of(
                                "type", "object",
                                "required", List.of("statuses"),
                                "additionalProperties", false,
                                "properties", Map.of("statuses", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string", "enum", List.of("OPEN", "DONE")))))),
                        "required", List.of("filters"),
                        "additionalProperties", false)));

        assertThatThrownBy(() -> registry.validate("query_tasks",
                Map.of("filters", Map.of("statuses", List.of("OPEN", "INVALID"))), nestedTools))
                .hasMessageContaining("枚举范围");
    }
}
