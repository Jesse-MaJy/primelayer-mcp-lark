package com.larkconnect.agent.agent;

import org.springframework.stereotype.Component;

@Component
public class IntentRouter {
    public IntentRoute route(String question) {
        String text = question == null ? "" : question.toLowerCase();
        if (containsAny(text, "绑定成功", "配置正常", "配置成功", "mcp正常", "mcp 正常", "mcp绑定", "mcp 绑定", "token配好", "token 配好", "token正常", "token 正常")) {
            return new IntentRoute(IntentCategory.MCP_CONFIG_STATUS, "mcp_config_status", "MCP 配置状态", "green", false);
        }
        if (containsAny(text, "为什么没有mcp", "为啥没有mcp", "没有 mcp", "mcp配置", "mcp 配置", "人员配置", "token", "绑定", "open_id")) {
            return new IntentRoute(IntentCategory.SYSTEM_CONFIG, "system_config_help", "配置说明", "orange", false);
        }
        if (containsAny(text, "天气", "气温", "下雨", "降雨", "空气质量", "外部数据", "实时数据", "weather", "temperature", "rain")) {
            return new IntentRoute(IntentCategory.WEATHER_EXTERNAL, "weather_external_data", "外部数据说明", "blue", false);
        }
        if (containsAny(text, "周报", "日报", "月报", "汇报", "总结", "report", "summary", "weekly", "daily")) {
            return new IntentRoute(IntentCategory.REPORT, "weekly_report", "项目报告", "blue", true);
        }
        if (containsAny(text,
                "primelayer", "mcp", "项目", "待办", "任务", "风险", "逾期", "进度", "健康度", "负责人", "验收", "施工",
                "task", "project", "risk", "progress")) {
            return new IntentRoute(IntentCategory.PROJECT_QUERY, "project_query", "项目查询", "blue", true);
        }
        return new IntentRoute(IntentCategory.GENERAL_CHAT, "general_chat", "Primelayer AI 回答", "blue", false);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
