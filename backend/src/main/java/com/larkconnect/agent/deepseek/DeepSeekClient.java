package com.larkconnect.agent.deepseek;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient implements DeepSeekConversationClient {
    private final AppProperties properties;
    private final AiRuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DeepSeekClient(AppProperties properties, AiRuntimeConfigService runtimeConfig,
                          ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl(properties.deepseek().baseUrl()).build();
    }

    @Override
    public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean allowTools) {
        return complete(model(), messages, tools, allowTools);
    }

    @Override
    public Completion complete(String selectedModel, List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools, boolean allowTools) {
        return requestCompletion(selectedModel, messages, tools, allowTools, false);
    }

    @Override
    public Completion formatPresentation(String selectedModel, List<Map<String, Object>> messages) {
        return requestCompletion(selectedModel, messages, List.of(), false, true);
    }

    private Completion requestCompletion(String selectedModel, List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools, boolean allowTools,
                                         boolean jsonOutput) {
        requireApiKey();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", selectedModel);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        if (allowTools && tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        if (jsonOutput) {
            body.put("response_format", Map.of("type", "json_object"));
            body.put("max_tokens", 12_000);
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return parseCompletion(response);
        } catch (Exception e) {
            throw new IllegalStateException(sanitize(e.getMessage()), e);
        }
    }

    @Override
    public String analyzeChunk(String toolName, String projectId, String json, int chunkIndex, int chunkCount) {
        return analyzeChunkWithUsage(model(), toolName, projectId, json, chunkIndex, chunkCount).content();
    }

    @Override
    public ChunkAnalysis analyzeChunkWithUsage(String selectedModel, String toolName, String projectId,
                                               String json, int chunkIndex, int chunkCount) {
        String prompt = """
                你是项目数据分块分析器。只分析给定 MCP 数据，不编造、不要求额外查询。
                工具：%s；项目：%s；分块：%d/%d。
                输出紧凑的结构化 JSON，字段必须包含 statistics、evidence、identifiers、risks、dataGaps。
                identifiers 必须保留后续工具调用可能需要的表单 ID、记录 ID、任务 ID。
                数据：%s
                """.formatted(toolName, projectId, chunkIndex, chunkCount, json);
        Completion completion = complete(selectedModel,
                List.of(Map.of("role", "user", "content", prompt)), List.of(), false);
        return new ChunkAnalysis(completion.content(), completion.inputTokens(), completion.outputTokens());
    }

    @Override
    public String model() {
        return runtimeConfig.currentModel();
    }

    public Map<String, Object> testConnection(String prompt) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", properties.deepseek().baseUrl());
        result.put("model", model());
        result.put("apiKeyConfigured", hasText(properties.deepseek().apiKey()));
        try {
            Completion completion = complete(List.of(Map.of("role", "user", "content",
                    hasText(prompt) ? prompt : "请只回复 OK，用于测试 DeepSeek API 连通性。")), List.of(), false);
            result.put("ok", true);
            result.put("answer", completion.content());
            result.put("inputTokens", completion.inputTokens());
            result.put("outputTokens", completion.outputTokens());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", sanitize(e.getMessage()));
        }
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Completion parseCompletion(Map<String, Object> response) throws Exception {
        JsonNode root = objectMapper.valueToTree(response);
        JsonNode messageNode = root.path("choices").path(0).path("message");
        if (messageNode.isMissingNode()) throw new IllegalStateException("DeepSeek 未返回 message");
        Map<String, Object> assistantMessage = objectMapper.convertValue(messageNode, new TypeReference<LinkedHashMap<String, Object>>() {});
        assistantMessage.put("role", "assistant");
        String content = messageNode.path("content").isNull() ? null : messageNode.path("content").asText(null);
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : messageNode.path("tool_calls")) {
            String id = call.path("id").asText();
            String name = call.path("function").path("name").asText();
            String rawArguments = call.path("function").path("arguments").asText("{}");
            Map<String, Object> arguments = objectMapper.readValue(rawArguments, new TypeReference<LinkedHashMap<String, Object>>() {});
            calls.add(new ToolCall(id, name, arguments));
        }
        int inputTokens = root.path("usage").path("prompt_tokens").asInt(0);
        int outputTokens = root.path("usage").path("completion_tokens").asInt(0);
        return new Completion(content, List.copyOf(calls), assistantMessage, inputTokens, outputTokens);
    }

    private void requireApiKey() {
        if (!hasText(properties.deepseek().apiKey())) throw new IllegalStateException("DeepSeek API key 未配置");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sanitize(String message) {
        String safe = message == null || message.isBlank() ? "DeepSeek 请求失败" : message;
        String apiKey = properties.deepseek().apiKey();
        return hasText(apiKey) ? safe.replace(apiKey, "***REDACTED***") : safe;
    }
}
