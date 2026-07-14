package com.larkconnect.agent.deepseek;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.ai.PromptDomain;
import com.larkconnect.agent.ai.PromptStage;
import com.larkconnect.agent.ai.PromptTemplateService;
import com.larkconnect.agent.audit.TraceEventService;
import com.larkconnect.agent.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient implements DeepSeekConversationClient {
    private static final int MAX_RESPONSE_ATTEMPTS = 2;
    private final AppProperties properties;
    private final AiRuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;
    private final RestClient decisionClient;
    private final RestClient formAnalysisClient;
    private final RestClient finalAnswerClient;
    private final TraceEventService traceEvents;
    private final PromptTemplateService promptTemplates;

    @Autowired
    public DeepSeekClient(AppProperties properties, AiRuntimeConfigService runtimeConfig,
                          ObjectMapper objectMapper, RestClient.Builder builder, TraceEventService traceEvents,
                          PromptTemplateService promptTemplates) {
        this.properties = properties;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        this.decisionClient = client(builder, properties.agent().modelTimeoutMs());
        this.formAnalysisClient = client(builder, properties.agent().formAnalysisTimeoutMs());
        this.finalAnswerClient = client(builder, properties.agent().finalAnswerTimeoutMs());
        this.traceEvents = traceEvents;
        this.promptTemplates = promptTemplates;
    }

    DeepSeekClient(AppProperties properties, AiRuntimeConfigService runtimeConfig,
                   ObjectMapper objectMapper, RestClient.Builder builder) {
        this(properties, runtimeConfig, objectMapper, builder, null, null);
    }

    DeepSeekClient(AppProperties properties, AiRuntimeConfigService runtimeConfig,
                   ObjectMapper objectMapper, RestClient.Builder builder, TraceEventService traceEvents) {
        this(properties, runtimeConfig, objectMapper, builder, traceEvents, null);
    }

    @Override
    public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean allowTools) {
        return complete(model(), messages, tools, allowTools);
    }

    @Override
    public Completion complete(String selectedModel, List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools, boolean allowTools) {
        return requestCompletion(null, selectedModel, messages, tools, allowTools, false, decisionClient);
    }

    @Override
    public Completion complete(TraceContext traceContext, String selectedModel,
                               List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                               boolean allowTools) {
        return requestCompletion(traceContext, selectedModel, messages, tools, allowTools, false, decisionClient);
    }

    @Override
    public Completion formatPresentation(String selectedModel, List<Map<String, Object>> messages) {
        return requestCompletion(null, selectedModel, messages, List.of(), false, true, finalAnswerClient);
    }

    @Override
    public Completion formatPresentation(TraceContext traceContext, String selectedModel,
                                         List<Map<String, Object>> messages) {
        return requestCompletion(traceContext, selectedModel, messages, List.of(), false, true, finalAnswerClient);
    }

    private Completion requestCompletion(TraceContext traceContext, String selectedModel, List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools, boolean allowTools,
                                         boolean jsonOutput, RestClient client) {
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
        TraceEventService.EventHandle trace = startTrace(traceContext, selectedModel, body);
        long started = System.nanoTime();
        try {
            ParsedResponse result = null;
            int retryCount = 0;
            for (int attempt = 1; attempt <= MAX_RESPONSE_ATTEMPTS; attempt++) {
                try {
                    result = executeCompletionRequest(client, body, attempt);
                    break;
                } catch (DeepSeekResponseFormatException e) {
                    if (attempt == MAX_RESPONSE_ATTEMPTS) throw e;
                    retryCount++;
                }
            }
            if (result == null) throw new IllegalStateException("DeepSeek 请求未返回可解析结果");
            Map<String, Object> response = result.response();
            Completion parsed = result.completion();
            long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            String purpose = traceContext != null && "decision".equals(traceContext.purpose())
                    && parsed.toolCalls().isEmpty() ? "final_answer" : traceContext == null ? null : traceContext.purpose();
            String label = "final_answer".equals(purpose) ? "DeepSeek 最终回答" : traceContext == null ? null : traceContext.label();
            if (traceEvents != null && trace != null) {
                traceEvents.complete(trace, TraceEventService.EventCompletion.succeeded()
                        .purpose(purpose).label(label).input(traceInputSummary(body)).output(response)
                        .usage(parsed.usage()).tokens(parsed.inputTokens(), parsed.outputTokens())
                        .metadata(Map.of("retryCount", retryCount)).build());
            }
            return new Completion(parsed.content(), parsed.toolCalls(), parsed.assistantMessage(),
                    parsed.inputTokens(), parsed.outputTokens(), trace == null ? null : trace.eventId(), latency, parsed.usage());
        } catch (Exception e) {
            if (traceEvents != null && trace != null) traceEvents.fail(trace, e);
            throw new IllegalStateException(sanitize(e.getMessage()), e);
        }
    }

    private ParsedResponse executeCompletionRequest(RestClient client, Map<String, Object> body, int attempt) {
        String raw = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            if (raw == null || raw.isBlank()) throw new IllegalStateException("空响应");
            Map<String, Object> response = objectMapper.readValue(raw,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            return new ParsedResponse(response, parseCompletion(response));
        } catch (Exception e) {
            throw malformedResponse(raw, attempt, e);
        }
    }

    private DeepSeekResponseFormatException malformedResponse(String raw, int attempt, Exception cause) {
        String value = raw == null ? "" : raw;
        String message = "DeepSeek 响应 JSON 无法解析：responseChars=" + value.length()
                + ", sha256=" + sha256(value) + ", cause=" + cause.getClass().getSimpleName()
                + "，已完成 " + attempt + " 次尝试";
        return new DeepSeekResponseFormatException(message, cause);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private TraceEventService.EventHandle startTrace(TraceContext context, String model, Map<String, Object> body) {
        if (traceEvents == null || context == null) return null;
        return traceEvents.start(TraceEventService.EventStart.builder(context.requestId(), "model_call")
                .parentEventId(context.parentEventId()).dependencyEventIds(context.dependencyEventIds())
                .roundIndex(context.roundIndex()).purpose(context.purpose()).label(context.label())
                .modelName(model).input(traceInputSummary(body)).metadata(context.metadata()).build());
    }

    private Map<String, Object> traceInputSummary(Map<String, Object> body) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", body.get("model"));
        summary.put("toolChoice", body.get("tool_choice"));
        summary.put("responseFormat", body.get("response_format"));
        Object messages = body.get("messages");
        if (messages instanceof List<?> list) {
            summary.put("messageCount", list.size());
            summary.put("messages", list.stream().map(value -> {
                if (!(value instanceof Map<?, ?> map)) return Map.of("value", String.valueOf(value));
                Object content = map.get("content");
                String text = content == null ? "" : String.valueOf(content);
                return Map.of("role", String.valueOf(map.get("role")),
                        "contentChars", text.length(),
                        "contentPreview", text.substring(0, Math.min(500, text.length())));
            }).toList());
        }
        if (body.get("tools") instanceof List<?> tools) summary.put("toolCount", tools.size());
        return summary;
    }

    @Override
    public String analyzeChunk(String toolName, String projectId, String json, int chunkIndex, int chunkCount) {
        return analyzeChunkWithUsage(model(), toolName, projectId, json, chunkIndex, chunkCount).content();
    }

    @Override
    public ChunkAnalysis analyzeChunkWithUsage(String selectedModel, String toolName, String projectId,
                                               String json, int chunkIndex, int chunkCount) {
        return analyzeChunkWithUsage(null, selectedModel, toolName, projectId, json, chunkIndex, chunkCount);
    }

    @Override
    public ChunkAnalysis analyzeChunkWithUsage(TraceContext traceContext, String selectedModel,
                                               String toolName, String projectId, String json,
                                               int chunkIndex, int chunkCount) {
        String business = renderPrompt(PromptStage.FORM_ANALYSIS, PromptDomain.detect(toolName), Map.of(
                "formName", toolName, "formId", projectId,
                "chunkIndex", chunkIndex, "chunkCount", chunkCount, "chunkData", json));
        String prompt = """
                你是项目数据分块分析器。只分析给定 MCP 数据，不编造、不要求额外查询。
                工具：%s；项目：%s；分块：%d/%d。
                输出紧凑的结构化 JSON，字段必须包含 statistics、evidence、identifiers、risks、dataGaps。
                identifiers 仅保留输入中已提供的业务编号或可用记录引用，禁止推断或恢复已移除的内部 ID。
                平台已完成权限和敏感字段清理；业务模板不得要求恢复已移除字段。
                业务分析指令：%s
                """.formatted(toolName, projectId, chunkIndex, chunkCount, business);
        Completion completion = requestCompletion(traceContext, selectedModel,
                List.of(Map.of("role", "user", "content", prompt)), List.of(), false, false,
                formAnalysisClient);
        return new ChunkAnalysis(completion.content(), completion.inputTokens(), completion.outputTokens(), completion.traceEventId());
    }

    @Override
    public ChunkAnalysis analyzeFormWithUsage(TraceContext traceContext, String selectedModel,
                                              String formId, String formName, String compactJson) {
        String business = renderPrompt(PromptStage.FORM_ANALYSIS, PromptDomain.detect(formName), Map.of(
                "formName", formName, "formId", formId, "chunkIndex", 1, "chunkCount", 1,
                "chunkData", compactJson, "chunkAnalyses", compactJson));
        String prompt = """
                你是单表数据分析器。只能分析给出的确定性统计、覆盖率和证据样本，不得请求工具或补写事实。
                表单：%s（%s）。
                输出紧凑 JSON，字段必须包含 summary、metrics、risks、evidence、dataGaps。
                若 coverageComplete=false，必须在 dataGaps 中明确说明，禁止形成完整性结论。
                业务分析指令：%s
                """.formatted(formName, formId, business);
        Completion completion = requestCompletion(traceContext, selectedModel,
                List.of(Map.of("role", "user", "content", prompt)), List.of(), false, false,
                formAnalysisClient);
        return new ChunkAnalysis(completion.content(), completion.inputTokens(), completion.outputTokens(),
                completion.traceEventId());
    }

    @Override
    public Completion finalizeAnswer(TraceContext traceContext, String selectedModel,
                                     List<Map<String, Object>> messages) {
        return requestCompletion(traceContext, selectedModel, messages, List.of(), false, false,
                finalAnswerClient);
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

    private RestClient client(RestClient.Builder builder, int timeoutMs) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeoutMs);
        requests.setReadTimeout(timeoutMs);
        return builder.clone().requestFactory(requests).baseUrl(properties.deepseek().baseUrl()).build();
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
        Map<String, Object> usage = root.path("usage").isObject()
                ? objectMapper.convertValue(root.path("usage"), new TypeReference<LinkedHashMap<String, Object>>() {})
                : Map.of();
        return new Completion(content, List.copyOf(calls), assistantMessage, inputTokens, outputTokens,
                null, 0, usage);
    }

    private void requireApiKey() {
        if (!hasText(properties.deepseek().apiKey())) throw new IllegalStateException("DeepSeek API key 未配置");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String renderPrompt(PromptStage stage, PromptDomain domain, Map<String, ?> variables) {
        if (promptTemplates == null) return String.valueOf(
                variables.containsKey("chunkData") ? variables.get("chunkData") : "");
        return promptTemplates.render(stage, domain, variables);
    }

    private String sanitize(String message) {
        String safe = message == null || message.isBlank() ? "DeepSeek 请求失败" : message;
        String apiKey = properties.deepseek().apiKey();
        return hasText(apiKey) ? safe.replace(apiKey, "***REDACTED***") : safe;
    }

    private record ParsedResponse(Map<String, Object> response, Completion completion) {}

    private static final class DeepSeekResponseFormatException extends RuntimeException {
        private DeepSeekResponseFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
