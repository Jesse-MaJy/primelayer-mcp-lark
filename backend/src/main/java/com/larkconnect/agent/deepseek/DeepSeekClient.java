package com.larkconnect.agent.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.mcp.McpToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final McpToolRegistry toolRegistry;

    public DeepSeekClient(AppProperties properties, ObjectMapper objectMapper, RestClient.Builder builder, McpToolRegistry toolRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl(properties.deepseek().baseUrl()).build();
        this.toolRegistry = toolRegistry;
    }

    public DeepSeekPlan plan(String requestId, String question, String chatType) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return heuristicPlan(question, chatType);
        }
        String schemaPrompt = """
                你是 Agent Gateway 的意图规划器。只能输出 JSON，不要输出解释。
                JSON 字段：intent, projectScope, projectHints, toolCalls, needClarification, clarificationQuestion, answerStyle。
                projectScope 只能是 single_project/current_chat_project/all_accessible_projects/unknown。
                可用工具：%s。
                用户问题：%s
                """.formatted(toolRegistry.describeTools(), question);
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(Map.of(
                        "model", properties.deepseek().model(),
                        "messages", List.of(Map.of("role", "user", "content", schemaPrompt)),
                        "response_format", Map.of("type", "json_object")
                ))
                .retrieve()
                .body(Map.class);
        try {
            JsonNode content = objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content");
            return objectMapper.readValue(content.asText(), DeepSeekPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("DeepSeek returned invalid plan", e);
        }
    }

    public String summarize(String question, List<Map<String, Object>> toolResults) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return fallbackSummary(question, toolResults);
        }
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(Map.of(
                        "model", properties.deepseek().model(),
                        "messages", List.of(Map.of("role", "user", "content", "基于以下 MCP 数据回答用户问题，必须说明数据范围和失败项目。\n问题：" + question + "\n数据：" + toolResults)),
                        "temperature", 0.2
                ))
                .retrieve()
                .body(Map.class);
        return objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content").asText();
    }

    private DeepSeekPlan heuristicPlan(String question, String chatType) {
        String scope = "group".equals(chatType) ? "current_chat_project" : (question.contains("所有") || question.contains("全部") || question.contains("跨项目") ? "all_accessible_projects" : "single_project");
        List<String> hints = question.contains("项目") && !"all_accessible_projects".equals(scope)
                ? List.of(question.replaceAll(".*?([A-Za-z0-9\\u4e00-\\u9fa5_-]+项目).*", "$1"))
                : List.of();
        return new DeepSeekPlan("project_query", scope, hints,
                List.of(new DeepSeekPlan.ToolCall("primelayer.query_tasks", Map.of("question", question))),
                false, null, "normal");
    }

    private String fallbackSummary(String question, List<Map<String, Object>> toolResults) {
        return "结论：已完成查询。\n\n关键数据：\n- 工具调用结果数：" + toolResults.size()
                + "\n\n数据范围：\n- 问题：" + question
                + "\n- 详情：" + toolResults;
    }
}
