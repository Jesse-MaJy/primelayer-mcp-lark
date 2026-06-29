package com.larkconnect.agent.mcp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpToolRegistry {
    private final List<ToolDefinition> tools = List.of(
            new ToolDefinition("primelayer.query_tasks", "查询 Primelayer 任务、逾期、风险和趋势", true, 30000),
            new ToolDefinition("primelayer.query_project_health", "查询项目健康度和主要风险", true, 30000)
    );

    public boolean isEnabled(String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name) && tool.enabled());
    }

    public String describeTools() {
        return tools.toString();
    }

    public void validate(String toolName, Map<String, Object> arguments) {
        if (!isEnabled(toolName)) {
            throw new IllegalArgumentException("未注册或未启用的 MCP 工具：" + toolName);
        }
        if (arguments == null) {
            throw new IllegalArgumentException("MCP 工具参数不能为空");
        }
    }

    public record ToolDefinition(String name, String description, boolean enabled, int timeoutMs) {}
}
