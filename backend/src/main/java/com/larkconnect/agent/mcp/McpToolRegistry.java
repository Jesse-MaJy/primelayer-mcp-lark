package com.larkconnect.agent.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpToolRegistry {
    private static final Set<String> KNOWN_READ_ONLY_TOOLS = Set.of(
            "get_base_form_info",
            "get_account_info",
            "get_organization_info",
            "match_form_resource",
            "list_form_resource",
            "get_form_definition",
            "query_form_data_list",
            "batch_get_form_value_detail",
            "query_todo_list",
            "get_process_approval_info",
            "get_report",
            "get_async_task_result",
            "get_folder_tree",
            "get_picture_folders",
            "get_file_by_name",
            "get_file_list",
            "get_file_detail",
            "get_file_reference",
            "get_picture_list",
            "get_picture_detail",
            "query_tasks"
    );

    private final List<ToolDefinition> tools = List.of(
            new ToolDefinition("primelayer.query_tasks", "查询 Primelayer 任务、逾期、风险和趋势", true, 30000),
            new ToolDefinition("primelayer.query_project_health", "查询项目健康度和主要风险", true, 30000),
            new ToolDefinition("get_account_info", "查询当前账号、项目、工作空间和租户信息", true, 30000)
    );

    public boolean isEnabled(String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name) && tool.enabled());
    }

    public String describeTools() {
        return tools.toString();
    }

    public void validate(String toolName, Map<String, Object> arguments) {
        if (!isAllowedReadOnlyTool(toolName)) {
            throw new IllegalArgumentException("未注册、未启用或非只读的 MCP 工具：" + toolName);
        }
        if (arguments == null) {
            throw new IllegalArgumentException("MCP 工具参数不能为空");
        }
    }

    public void validate(String toolName, Map<String, Object> arguments, List<Map<String, Object>> availableTools) {
        validate(toolName, arguments);
        Map<String, Object> discovered = availableTools.stream()
                .filter(tool -> toolName.equals(String.valueOf(tool.get("name"))))
                .findFirst().orElse(null);
        if (discovered == null) {
            throw new IllegalArgumentException("MCP 服务未暴露工具：" + toolName);
        }
        if (!isAllowedDiscoveredTool(discovered)) {
            throw new IllegalArgumentException("MCP 工具未声明为只读或被标记为破坏性操作：" + toolName);
        }
        validateSchema(arguments, discovered.get("inputSchema"));
    }

    public List<Map<String, Object>> filterDiscoveredTools(List<Map<String, Object>> discoveredTools) {
        List<Map<String, Object>> filtered = discoveredTools.stream()
                .filter(this::isAllowedDiscoveredTool)
                .map(this::sanitizeTool)
                .toList();
        return filtered;
    }

    public List<Map<String, Object>> defaultToolMaps() {
        return tools.stream()
                .filter(ToolDefinition::enabled)
                .map(tool -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", tool.name());
                    item.put("description", tool.description());
                    item.put("inputSchema", Map.of(
                            "type", "object",
                            "properties", Map.of("question", Map.of("type", "string"))
                    ));
                    return item;
                })
                .toList();
    }

    private Map<String, Object> sanitizeTool(Map<String, Object> tool) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        sanitized.put("name", tool.get("name"));
        sanitized.put("description", tool.get("description"));
        if (tool.containsKey("inputSchema")) {
            sanitized.put("inputSchema", tool.get("inputSchema"));
        }
        if (tool.containsKey("annotations")) {
            sanitized.put("annotations", tool.get("annotations"));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private void validateSchema(Map<String, Object> arguments, Object schemaValue) {
        validateValue(arguments, schemaValue, "arguments");
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value, Object schemaValue, String path) {
        if (!(schemaValue instanceof Map<?, ?> rawSchema)) return;
        Map<String, Object> schema = (Map<String, Object>) rawSchema;
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> allowed && !allowed.contains(value)) {
            throw new IllegalArgumentException("MCP 参数 " + path + " 不在允许的枚举范围内");
        }
        String expected = String.valueOf(schema.getOrDefault("type", ""));
        if (!expected.isBlank() && !matchesType(value, expected)) {
            throw new IllegalArgumentException("MCP 参数 " + path + " 类型应为 " + expected);
        }
        if (value == null) return;
        if (value instanceof List<?> items) {
            Object itemSchema = schema.get("items");
            for (int i = 0; i < items.size(); i++) validateValue(items.get(i), itemSchema, path + "[" + i + "]");
            return;
        }
        if (!(value instanceof Map<?, ?> rawValue)) return;
        Map<String, Object> object = (Map<String, Object>) rawValue;
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> rawProperties
                ? (Map<String, Object>) rawProperties : Map.of();
        if (schema.get("required") instanceof List<?> required) {
            for (Object key : required) {
                if (!object.containsKey(String.valueOf(key))) {
                    throw new IllegalArgumentException("MCP 工具缺少必填参数：" + path + "." + key);
                }
            }
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            for (String key : object.keySet()) {
                if (!properties.containsKey(key)) throw new IllegalArgumentException("MCP 工具包含未定义参数：" + path + "." + key);
            }
        }
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            validateValue(entry.getValue(), properties.get(entry.getKey()), path + "." + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isAllowedDiscoveredTool(Map<String, Object> tool) {
        String name = String.valueOf(tool.get("name"));
        if (!isAllowedReadOnlyTool(name)) return false;
        if (!(tool.get("annotations") instanceof Map<?, ?> rawAnnotations)) {
            return KNOWN_READ_ONLY_TOOLS.contains(name) || isEnabled(name);
        }
        Map<String, Object> annotations = (Map<String, Object>) rawAnnotations;
        return Boolean.TRUE.equals(annotations.get("readOnlyHint"))
                && !Boolean.TRUE.equals(annotations.get("destructiveHint"));
    }

    private boolean matchesType(Object value, String expected) {
        if (value == null) return true;
        return switch (expected) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    private boolean isAllowedReadOnlyTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (isMutatingName(toolName)) {
            return false;
        }
        if (isEnabled(toolName)) {
            return true;
        }
        if (KNOWN_READ_ONLY_TOOLS.contains(toolName)) {
            return true;
        }
        return toolName.startsWith("get_")
                || toolName.startsWith("list_")
                || toolName.startsWith("query_")
                || toolName.startsWith("search_")
                || toolName.startsWith("primelayer.query_")
                || toolName.contains(".get_")
                || toolName.contains(".list_")
                || toolName.contains(".query_")
                || toolName.contains(".search_");
    }

    private boolean isMutatingName(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.matches(".*(^|[._-])(create|update|delete|remove|write|patch)([._-]|$).*");
    }

    public record ToolDefinition(String name, String description, boolean enabled, int timeoutMs) {}
}
