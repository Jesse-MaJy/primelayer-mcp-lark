package com.larkconnect.agent.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.mcp.McpToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                        "response_format", Map.of("type", "json_object"),
                        "temperature", 0
                ))
                .retrieve()
                .body(Map.class);
        try {
            JsonNode content = objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content");
            return normalizePlan(content.asText(), question, chatType);
        } catch (Exception e) {
            DeepSeekPlan fallback = heuristicPlan(question, chatType);
            Map<String, Object> args = new LinkedHashMap<>(fallback.toolCalls().get(0).arguments());
            args.put("plannerFallbackReason", "DeepSeek returned invalid plan: " + sanitizeError(e.getMessage()));
            return new DeepSeekPlan(
                    fallback.intent(),
                    fallback.projectScope(),
                    fallback.projectHints(),
                    List.of(new DeepSeekPlan.ToolCall(fallback.toolCalls().get(0).toolName(), args)),
                    false,
                    null,
                    "fallback"
            );
        }
    }

    public Map<String, Object> testConnection(String prompt) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", properties.deepseek().baseUrl());
        result.put("model", properties.deepseek().model());
        result.put("apiKeyConfigured", properties.deepseek().apiKey() != null && !properties.deepseek().apiKey().isBlank());
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            result.put("ok", false);
            result.put("error", "DeepSeek API key 未配置");
            result.put("latencyMs", System.currentTimeMillis() - started);
            return result;
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                    .body(Map.of(
                            "model", properties.deepseek().model(),
                            "messages", List.of(Map.of(
                                    "role", "user",
                                    "content", hasText(prompt) ? prompt : "请只回复 OK，用于测试 DeepSeek API 连通性。"
                            )),
                            "temperature", 0,
                            "max_tokens", 64
                    ))
                    .retrieve()
                    .body(Map.class);
            JsonNode root = objectMapper.valueToTree(response);
            result.put("ok", true);
            result.put("id", root.path("id").asText(""));
            result.put("answer", root.path("choices").path(0).path("message").path("content").asText(""));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", sanitizeError(e.getMessage()));
        }
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    public Map<String, Object> planMcpDebugCall(String question, List<Map<String, Object>> tools) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return fallbackMcpPlan(question, tools);
        }
        try {
            String prompt = """
                    你是 MCP 调试助手。根据用户问题和可用 MCP 工具，选择最合适的一个工具，并生成参数。
                    只能输出 JSON，不要输出解释。JSON 字段：
                    toolName: string
                    arguments: object
                    reason: string

                    规则：
                    - toolName 必须来自可用工具列表。
                    - arguments 必须符合工具 inputSchema；如果工具无需参数，返回空对象。
                    - 不要编造 token、密钥或认证信息。

                    用户问题：%s
                    可用工具：%s
                    """.formatted(question, objectMapper.writeValueAsString(tools));
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                    .body(Map.of(
                            "model", properties.deepseek().model(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            "response_format", Map.of("type", "json_object"),
                            "temperature", 0
                    ))
                    .retrieve()
                    .body(Map.class);
            JsonNode content = objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content");
            return objectMapper.readValue(content.asText(), Map.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>(fallbackMcpPlan(question, tools));
            fallback.put("plannerError", sanitizeError(e.getMessage()));
            return fallback;
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

    public String answerGeneral(String question) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return "DeepSeek API key 未配置，暂时无法回答这个通用问题。";
        }
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(Map.of(
                        "model", properties.deepseek().model(),
                        "messages", List.of(
                                Map.of(
                                        "role", "system",
                                        "content", "你是 Lark Connect 的通用问答助手。直接回答用户问题。若问题需要实时数据、外部系统数据或地理位置但当前未提供工具，请说明限制，不要编造。"
                                ),
                                Map.of("role", "user", "content", question)
                        ),
                        "temperature", 0.2
                ))
                .retrieve()
                .body(Map.class);
        return objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content").asText();
    }

    private DeepSeekPlan heuristicPlan(String question, String chatType) {
        String safeQuestion = question == null ? "" : question;
        String scope = "group".equals(chatType) ? "current_chat_project" : (safeQuestion.contains("所有") || safeQuestion.contains("全部") || safeQuestion.contains("跨项目") ? "all_accessible_projects" : "single_project");
        List<String> hints = safeQuestion.contains("项目") && !"all_accessible_projects".equals(scope)
                ? List.of(question.replaceAll(".*?([A-Za-z0-9\\u4e00-\\u9fa5_-]+项目).*", "$1"))
                : List.of();
        String toolName = containsAny(safeQuestion, "状态", "情况", "施工", "进度", "健康度", "概况", "整体")
                ? "primelayer.query_project_health"
                : "primelayer.query_tasks";
        return new DeepSeekPlan("project_query", scope, hints,
                List.of(new DeepSeekPlan.ToolCall(toolName, Map.of("question", safeQuestion))),
                false, null, "normal");
    }

    private DeepSeekPlan normalizePlan(String rawContent, String question, String chatType) throws Exception {
        String json = extractJsonObject(rawContent);
        JsonNode root = objectMapper.readTree(json);
        String intent = text(root, "intent", "skill", "skillId");
        String projectScope = normalizeProjectScope(text(root, "projectScope", "project_scope", "scope"), chatType, question);
        List<String> projectHints = textList(root, "projectHints", "project_hints", "projects", "projectNames");
        List<DeepSeekPlan.ToolCall> toolCalls = normalizeToolCalls(root.path("toolCalls"), question);
        if (toolCalls.isEmpty()) {
            toolCalls = normalizeToolCalls(root.path("tool_calls"), question);
        }
        boolean needClarification = bool(root, "needClarification", "need_clarification", "requiresClarification");
        String clarificationQuestion = text(root, "clarificationQuestion", "clarification_question", "questionToAsk");
        String answerStyle = text(root, "answerStyle", "answer_style");

        DeepSeekPlan fallback = heuristicPlan(question, chatType);
        if (!hasText(intent)) {
            intent = fallback.intent();
        }
        if (toolCalls.isEmpty() && !needClarification) {
            toolCalls = fallback.toolCalls();
        }
        if (projectHints.isEmpty()) {
            projectHints = fallback.projectHints();
        }
        if (!hasText(answerStyle)) {
            answerStyle = "normal";
        }
        return new DeepSeekPlan(intent, projectScope, projectHints, toolCalls, needClarification, clarificationQuestion, answerStyle);
    }

    private List<DeepSeekPlan.ToolCall> normalizeToolCalls(JsonNode node, String question) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<DeepSeekPlan.ToolCall> calls = new ArrayList<>();
        if (node.isObject()) {
            DeepSeekPlan.ToolCall call = normalizeToolCall(node, question);
            if (call != null) {
                calls.add(call);
            }
            return calls;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                DeepSeekPlan.ToolCall call = normalizeToolCall(item, question);
                if (call != null) {
                    calls.add(call);
                }
            }
        }
        return calls;
    }

    private DeepSeekPlan.ToolCall normalizeToolCall(JsonNode node, String question) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String toolName = text(node, "toolName", "tool_name", "name");
        if (!hasText(toolName) || !toolRegistry.isEnabled(toolName)) {
            toolName = heuristicPlan(question, "p2p").toolCalls().get(0).toolName();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        JsonNode argsNode = node.path("arguments");
        if (argsNode.isMissingNode() || argsNode.isNull()) {
            argsNode = node.path("args");
        }
        if (argsNode.isObject()) {
            arguments.putAll(objectMapper.convertValue(argsNode, Map.class));
        }
        arguments.putIfAbsent("question", question == null ? "" : question);
        return new DeepSeekPlan.ToolCall(toolName, arguments);
    }

    private String extractJsonObject(String content) {
        if (!hasText(content)) {
            throw new IllegalArgumentException("empty content");
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("content is not a JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    private String normalizeProjectScope(String value, String chatType, String question) {
        if (hasText(value)) {
            String normalized = value.trim();
            if (List.of("single_project", "current_chat_project", "all_accessible_projects", "unknown").contains(normalized)) {
                return normalized;
            }
        }
        return heuristicPlan(question, chatType).projectScope();
    }

    private List<String> textList(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode item : node) {
                    if (hasText(item.asText())) {
                        values.add(item.asText());
                    }
                }
                return values;
            }
            if (node.isTextual() && hasText(node.asText())) {
                return List.of(node.asText());
            }
        }
        return List.of();
    }

    private boolean bool(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isTextual()) {
                return Boolean.parseBoolean(node.asText());
            }
        }
        return false;
    }

    private String text(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (!node.isMissingNode() && !node.isNull() && hasText(node.asText())) {
                return node.asText();
            }
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String fallbackSummary(String question, List<Map<String, Object>> toolResults) {
        return "结论：已完成查询。\n\n关键数据：\n- 工具调用结果数：" + toolResults.size()
                + "\n\n数据范围：\n- 问题：" + question
                + "\n- 详情：" + toolResults;
    }

    private Map<String, Object> fallbackMcpPlan(String question, List<Map<String, Object>> tools) {
        Map<String, Object> selected = tools.stream()
                .filter(tool -> "get_account_info".equals(String.valueOf(tool.get("name"))))
                .findFirst()
                .orElseGet(() -> tools.isEmpty() ? Map.of() : tools.get(0));
        String toolName = String.valueOf(selected.getOrDefault("name", ""));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("toolName", toolName);
        plan.put("arguments", Map.of());
        plan.put("reason", hasText(toolName) ? "DeepSeek 不可用时使用默认可用工具测试 MCP 连通性" : "没有发现可用 MCP 工具");
        plan.put("question", question);
        return plan;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sanitizeError(String message) {
        if (message == null) {
            return "DeepSeek 请求失败";
        }
        String apiKey = properties.deepseek().apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            return message.replace(apiKey, "***REDACTED***");
        }
        return message;
    }

    public DeepSeekPlan planMultiTool(String question, List<Map<String, Object>> availableTools) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return heuristicPlan(question, "p2p");
        }
        try {
            String toolsJson = objectMapper.writeValueAsString(availableTools);
            String prompt = """
                    你是 Agent Gateway 的意图规划器。只能输出 JSON，不要输出解释。
                    JSON 字段：
                    {
                      "intent": "string",
                      "projectScope": "single_project|current_chat_project|all_accessible_projects|unknown",
                      "projectHints": ["项目名"],
                      "toolCalls": [
                        {
                          "toolName": "必须是可用工具列表中的名称",
                          "arguments": {"参数名": "值"},
                          "reason": "为什么选择这个工具"
                        }
                      ],
                      "needClarification": false,
                      "clarificationQuestion": null,
                      "answerStyle": "normal"
                    }

                    重要规则：
                    - toolCalls 是数组，可以包含多个工具（0-5个），一次性规划所有需要调用的工具
                    - 每个 toolName 必须严格来自可用工具列表
                    - 不要选择功能重复的工具，优先选择名称中包含 query_/get_/list_/search_ 的只读工具
                    - 如果用户问题涉及多个维度（如同时问任务和健康度），选择一个覆盖最广的组合
                    - arguments 不传 token、密钥或认证信息

                    可用工具：%s
                    用户问题：%s
                    """.formatted(toolsJson, question);

            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                    .body(Map.of(
                            "model", properties.deepseek().model(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            "response_format", Map.of("type", "json_object"),
                            "temperature", 0
                    ))
                    .retrieve()
                    .body(Map.class);

            JsonNode content = objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content");
            return normalizePlan(content.asText(), question, "p2p");
        } catch (Exception e) {
            return heuristicPlan(question, "p2p");
        }
    }

    public String analyzePerTool(String toolName, List<Map<String, Object>> allPageResults) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return "工具 " + toolName + " 返回 " + allPageResults.size() + " 页数据（未分析）";
        }
        try {
            String dataJson = objectMapper.writeValueAsString(allPageResults);
            String prompt = """
                    你是数据分析助手。基于 MCP 工具返回的全部数据做独立分析。

                    工具名称：%s
                    数据（已合并所有分页）：%s

                    请输出结构化分析：
                    1. 数据总量和关键统计
                    2. 数据中的主要发现和趋势
                    3. 异常值和需要关注的风险点
                    4. 数据质量评估（是否完整、是否有缺失）

                    只分析数据本身，不要提出需要补充查询的建议。
                    """.formatted(toolName,
                    dataJson.length() > 8000 ? dataJson.substring(0, 8000) + "...(truncated)" : dataJson);

            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                    .body(Map.of(
                            "model", properties.deepseek().model(),
                            "messages", List.of(Map.of("role", "user", "content", prompt)),
                            "temperature", 0.2
                    ))
                    .retrieve()
                    .body(Map.class);

            return objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            return "工具 " + toolName + " 数据分析失败: " + sanitizeError(e.getMessage());
        }
    }

    public String analyzeCross(String question, List<PerToolAnalysis> perToolAnalyses) {
        if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
            return fallbackSummary(question, List.of());
        }
        try {
            StringBuilder analyses = new StringBuilder();
            for (int i = 0; i < perToolAnalyses.size(); i++) {
                PerToolAnalysis a = perToolAnalyses.get(i);
                analyses.append("[").append(i + 1).append("] 工具: ").append(a.toolName()).append("\n")
                        .append("分析: ").append(a.analysis()).append("\n\n");
            }

            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                    .body(Map.of(
                            "model", properties.deepseek().model(),
                            "messages", List.of(Map.of("role", "user", "content", """
                                    你是商业数据分析师。基于多个维度独立分析结果，做交叉综合分析，输出最终答案。

                                    用户原始问题：%s

                                    各维度独立分析：
                                    %s

                                    要求：
                                    - 用自然语言回答用户问题，直接给结论
                                    - 引用各维度关键数据支撑结论
                                    - 说明数据范围和局限性
                                    - 不要编造数据
                                    """.formatted(question, analyses.toString()))),
                            "temperature", 0.2
                    ))
                    .retrieve()
                    .body(Map.class);

            return objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            return fallbackSummary(question, List.of());
        }
    }

    public record PerToolAnalysis(String toolName, String analysis) {}
}
