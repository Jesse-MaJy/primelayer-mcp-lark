package com.larkconnect.agent.mcp;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpToolRegistry {
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
        boolean discovered = availableTools.stream()
                .anyMatch(tool -> toolName.equals(String.valueOf(tool.get("name"))));
        if (!discovered) {
            throw new IllegalArgumentException("MCP 服务未暴露工具：" + toolName);
        }
    }

    public List<Map<String, Object>> filterDiscoveredTools(List<Map<String, Object>> discoveredTools) {
        List<Map<String, Object>> filtered = discoveredTools.stream()
                .filter(tool -> isAllowedReadOnlyTool(String.valueOf(tool.get("name"))))
                .map(this::sanitizeTool)
                .toList();
        return filtered.isEmpty() ? defaultToolMaps() : filtered;
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
        return sanitized;
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
        return lower.startsWith("create_")
                || lower.startsWith("update_")
                || lower.startsWith("delete_")
                || lower.startsWith("remove_")
                || lower.startsWith("write_")
                || lower.startsWith("patch_")
                || lower.contains(".create_")
                || lower.contains(".update_")
                || lower.contains(".delete_")
                || lower.contains(".remove_")
                || lower.contains(".write_")
                || lower.contains(".patch_");
    }

    public record ToolDefinition(String name, String description, boolean enabled, int timeoutMs) {}
}
