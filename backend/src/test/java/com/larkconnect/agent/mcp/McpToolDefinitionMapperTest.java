package com.larkconnect.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolDefinitionMapperTest {
    private final McpToolDefinitionMapper mapper = new McpToolDefinitionMapper();

    @Test
    void wrapsMcpSchemaWithProjectsAndArgumentsAndMapsUnsafeName() {
        McpToolDefinitionMapper.MappedTools mapped = mapper.map(List.of(Map.of(
                "name", "primelayer.query_tasks",
                "description", "查询任务",
                "inputSchema", Map.of("type", "object", "properties", Map.of("status", Map.of("type", "string")))
        )));

        Map<String, Object> function = cast(cast(mapped.deepSeekTools().get(0).get("function")));
        assertThat(function.get("name")).isEqualTo("mcp_primelayer_query_tasks");
        Map<String, Object> parameters = cast(function.get("parameters"));
        assertThat(cast(parameters.get("properties"))).containsKeys("projectIds", "arguments");
        assertThat(mapped.originalName("mcp_primelayer_query_tasks")).isEqualTo("primelayer.query_tasks");
    }

    @Test
    void givesCollidingAliasesStableSuffixes() {
        McpToolDefinitionMapper.MappedTools mapped = mapper.map(List.of(
                Map.of("name", "a.b", "description", "one", "inputSchema", Map.of("type", "object")),
                Map.of("name", "a-b", "description", "two", "inputSchema", Map.of("type", "object"))
        ));

        assertThat(mapped.aliases().keySet()).containsExactly("mcp_a_b", "mcp_a_b_2");
    }

    @Test
    void rejectsUnknownAlias() {
        McpToolDefinitionMapper.MappedTools mapped = mapper.map(List.of());
        assertThatThrownBy(() -> mapped.originalName("invented_tool"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removesServerManagedArgumentsFromModelVisibleRequiredSchema() {
        McpToolDefinitionMapper.MappedTools mapped = mapper.map(List.of(Map.of(
                "name", "match_form_resource",
                "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("name", "project_id", "primelayer_user_id"),
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "project_id", Map.of("type", "string"),
                                "primelayer_user_id", Map.of("type", "string"))))));

        Map<String, Object> function = cast(mapped.deepSeekTools().get(0).get("function"));
        Map<String, Object> parameters = cast(function.get("parameters"));
        Map<String, Object> arguments = cast(cast(parameters.get("properties")).get("arguments"));

        assertThat(((List<?>) arguments.get("required")).stream().map(String::valueOf).toList())
                .containsExactly("name");
        assertThat(cast(arguments.get("properties"))).containsOnlyKeys("name");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
