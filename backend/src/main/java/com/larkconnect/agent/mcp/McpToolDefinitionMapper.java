package com.larkconnect.agent.mcp;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpToolDefinitionMapper {
    private static final Set<String> SERVER_MANAGED_ARGUMENTS = Set.of(
            "project_id", "projectId", "primelayer_user_id", "primelayerUserId");

    public MappedTools map(List<Map<String, Object>> mcpTools) {
        Map<String, String> aliases = new LinkedHashMap<>();
        List<Map<String, Object>> deepSeekTools = new ArrayList<>();
        for (Map<String, Object> tool : mcpTools == null ? List.<Map<String, Object>>of() : mcpTools) {
            String originalName = String.valueOf(tool.getOrDefault("name", ""));
            if (originalName.isBlank()) continue;
            String base = aliasFor(originalName);
            String alias = base;
            int suffix = 2;
            while (aliases.containsKey(alias)) alias = trim(base, 61) + "_" + suffix++;
            aliases.put(alias, originalName);

            Map<String, Object> originalSchema = modelVisibleSchema(asMap(tool.get("inputSchema")));
            Map<String, Object> properties = new LinkedHashMap<>();
            Map<String, Object> projectItems = new LinkedHashMap<>();
            projectItems.put("type", "string");
            List<String> supportedProjects = stringList(tool.get("supportedProjectIds"));
            if (!supportedProjects.isEmpty()) projectItems.put("enum", supportedProjects);
            properties.put("projectIds", Map.of(
                    "type", "array",
                    "items", projectItems,
                    "description", supportedProjects.isEmpty()
                            ? "要查询的项目 ID；必须来自系统提示中的可访问项目"
                            : "该工具定义支持的项目 ID：" + String.join(", ", supportedProjects)
            ));
            properties.put("arguments", originalSchema.isEmpty() ? Map.of("type", "object") : originalSchema);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", List.of("projectIds", "arguments"));
            parameters.put("additionalProperties", false);

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", alias);
            function.put("description", String.valueOf(tool.getOrDefault("description", originalName)));
            function.put("parameters", parameters);
            deepSeekTools.add(Map.of("type", "function", "function", function));
        }
        return new MappedTools(List.copyOf(deepSeekTools), Collections.unmodifiableMap(new LinkedHashMap<>(aliases)));
    }

    private String aliasFor(String name) {
        String safe = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (safe.isBlank()) safe = "tool";
        return trim("mcp_" + safe, 64);
    }

    private String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> modelVisibleSchema(Map<String, Object> schema) {
        if (schema.isEmpty()) return schema;
        Map<String, Object> visible = new LinkedHashMap<>(schema);
        if (schema.get("properties") instanceof Map<?, ?> rawProperties) {
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) rawProperties);
            SERVER_MANAGED_ARGUMENTS.forEach(properties::remove);
            visible.put("properties", properties);
        }
        if (schema.get("required") instanceof List<?> required) {
            visible.put("required", required.stream().map(String::valueOf)
                    .filter(name -> !SERVER_MANAGED_ARGUMENTS.contains(name)).toList());
        }
        return visible;
    }

    public record MappedTools(List<Map<String, Object>> deepSeekTools, Map<String, String> aliases) {
        public String originalName(String alias) {
            String value = aliases.get(alias);
            if (value == null) throw new IllegalArgumentException("DeepSeek 返回了未知 MCP 工具：" + alias);
            return value;
        }

        public String aliasFor(String originalName) {
            return aliases.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(originalName))
                    .map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("MCP 服务未暴露工具：" + originalName));
        }
    }
}
