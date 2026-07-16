package com.larkconnect.agent.agent;

import org.springframework.stereotype.Component;

@Component
public class ControlCommandRouter {
    public Command classify(String question) {
        String text = question == null ? "" : question.toLowerCase();
        if (containsAny(text, "检查 mcp", "检查mcp", "mcp 配置状态", "mcp配置状态", "配置正常", "mcp正常", "mcp 正常")) {
            return Command.MCP_CONFIG_STATUS;
        }
        if (containsAny(text, "mcp 配置", "mcp配置", "token 配置", "token配置", "人员绑定", "项目绑定")) {
            return Command.CONFIG_HELP;
        }
        return Command.BUSINESS_QUERY;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    public enum Command { BUSINESS_QUERY, MCP_CONFIG_STATUS, CONFIG_HELP }
}
