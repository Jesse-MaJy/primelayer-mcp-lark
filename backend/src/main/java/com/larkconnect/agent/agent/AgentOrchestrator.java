package com.larkconnect.agent.agent;

import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.audit.ChainTrace;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestrator {
    private final AgentTaskService taskService;
    private final UnifiedQueryService unifiedQueryService;
    private final TokenResolver tokenResolver;
    private final McpAdapter mcpAdapter;
    private final FeishuClient feishuClient;
    private final AuditService auditService;
    private final ChainTraceService chainTraceService;
    private final ControlCommandRouter controlRouter;
    private final int maxProjects;
    private DeliveryOutboxRepository deliveryOutbox;
    private ObjectMapper objectMapper;
    private Clock clock;

    @Autowired
    public AgentOrchestrator(AgentTaskService taskService, UnifiedQueryService unifiedQueryService,
                             TokenResolver tokenResolver, McpAdapter mcpAdapter, FeishuClient feishuClient,
                             AuditService auditService, ChainTraceService chainTraceService,
                             ControlCommandRouter controlRouter, AppProperties properties,
                             DeliveryOutboxRepository deliveryOutbox, ObjectMapper objectMapper, Clock clock) {
        this(taskService, unifiedQueryService, tokenResolver, mcpAdapter, feishuClient, auditService,
                chainTraceService, controlRouter, properties.agent().maxProjectsPerQuery());
        this.deliveryOutbox = deliveryOutbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    AgentOrchestrator(AgentTaskService taskService, UnifiedQueryService unifiedQueryService,
                      TokenResolver tokenResolver, McpAdapter mcpAdapter, FeishuClient feishuClient,
                      AuditService auditService, ChainTraceService chainTraceService,
                      ControlCommandRouter controlRouter, int maxProjects) {
        this.taskService = taskService;
        this.unifiedQueryService = unifiedQueryService;
        this.tokenResolver = tokenResolver;
        this.mcpAdapter = mcpAdapter;
        this.feishuClient = feishuClient;
        this.auditService = auditService;
        this.chainTraceService = chainTraceService;
        this.controlRouter = controlRouter;
        this.maxProjects = maxProjects;
        this.deliveryOutbox = null;
        this.objectMapper = null;
        this.clock = Clock.systemUTC();
    }

    public ProcessResult process(String requestId) {
        long started = System.currentTimeMillis();
        Map<String, Object> task = taskService.loadTask(requestId);
        String question = text(task, "message_text");
        String messageId = text(task, "feishu_message_id");
        String chatId = text(task, "feishu_chat_id");
        String openId = text(task, "feishu_open_id");
        String chatType = text(task, "chat_type");

        ControlCommandRouter.Command command = controlRouter.classify(question);
        if (command == ControlCommandRouter.Command.MCP_CONFIG_STATUS) {
            processMcpConfigStatus(requestId, question, messageId, chatId, openId, chatType, started);
            return ProcessResult.success();
        }
        if (command == ControlCommandRouter.Command.CONFIG_HELP) {
            String answer = configurationHint();
            feishuClient.replyAnswerCard(messageId, question, answer, "配置说明", "orange");
            auditService.writeMain(requestId, openId, chatId, null, List.of(), question,
                    "system_config_help", answer, System.currentTimeMillis() - started, null);
            return ProcessResult.success();
        }

        try {
            UnifiedQueryService.QueryResult result = unifiedQueryService.query(
                    new UnifiedQueryService.QueryRequest(requestId, question, chatType, openId, chatId,
                            instant(task.get("created_at"))));
            boolean partial = "hard_timeout".equals(result.stopReason())
                    || "partial_coverage".equals(result.stopReason())
                    || "deepseek_response_error".equals(result.stopReason())
                    || !result.failures().isEmpty();
            String title = partial ? "项目数据分析（部分结果）"
                    : "mcp_deepseek".equals(result.path()) ? "项目数据分析" : "DeepSeek 回答";
            auditService.writeModel(requestId, result.model(), result.path(),
                    auditSummary(result),
                    result.presentationJson() == null ? result.answer() : result.presentationJson(),
                    Status.SUCCEEDED, result.latencyMs(), null);
            auditService.writeMain(requestId, openId, chatId, null, result.projectsQueried(), question,
                    result.path(), result.answer(), result.presentationJson(), System.currentTimeMillis() - started,
                    result.failures().isEmpty() ? null : String.join("；", result.failures()));
            chainTraceService.save(requestId, traceFor(requestId, result));
            writeTraceStatus(requestId);
            try {
                FeishuClient.DeliveryResult delivery;
                if (deliveryOutbox != null && objectMapper != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("messageId", messageId);
                    payload.put("requestId", requestId);
                    payload.put("question", question);
                    payload.put("answer", result.answer());
                    payload.put("presentationJson", result.presentationJson());
                    payload.put("title", title);
                    payload.put("template", partial ? "orange" : "blue");
                    deliveryOutbox.enqueueOnce(requestId, DeliveryType.FINAL_RESULT,
                            objectMapper.writeValueAsString(payload), clock.instant());
                    delivery = null;
                } else {
                    delivery = feishuClient.replyAnswerFeedbackCard(
                            messageId, requestId, question, result.presentation(), title, partial ? "orange" : "blue");
                }
                if (delivery != null && delivery.warning() != null) {
                    auditService.writeMain(requestId, openId, chatId, null, result.projectsQueried(), question,
                            result.path(), result.answer(), result.presentationJson(),
                            System.currentTimeMillis() - started, delivery.warning());
                }
            } catch (Exception deliveryFailure) {
                String error = "飞书消息发送失败：" + FeishuClient.deliveryFailureReason(deliveryFailure);
                String notification = "AI 已完成回答，但飞书消息发送失败："
                        + FeishuClient.deliveryFailureReason(deliveryFailure);
                try {
                    feishuClient.replyAnswerCard(messageId, question, notification, "飞书消息发送失败", "orange");
                } catch (Exception ignored) {
                    // 保留首次发送失败，避免补偿消息异常覆盖根因。
                }
                auditService.writeMain(requestId, openId, chatId, null, result.projectsQueried(), question,
                        result.path(), result.answer(), result.presentationJson(),
                        System.currentTimeMillis() - started, error);
                auditService.updateProcessingLatency(requestId, System.currentTimeMillis() - started);
                return ProcessResult.failed(error);
            }
            auditService.updateProcessingLatency(requestId, System.currentTimeMillis() - started);
            return partial ? ProcessResult.partial(result.stopReason()) : ProcessResult.success();
        } catch (UnifiedQueryService.QueryPendingException pending) {
            return ProcessResult.pending(pending.resumeAfter());
        } catch (Exception e) {
            String error = readable(e);
            String answer = "AI 服务暂不可用，已记录本次请求，请稍后重试。";
            UnifiedQueryService.QueryExecutionException queryError = e instanceof UnifiedQueryService.QueryExecutionException q ? q : null;
            feishuClient.replyAnswerCard(messageId, question, answer, "AI 服务异常", "orange");
            auditService.writeModel(requestId, queryError == null ? "deepseek" : queryError.model(), "unified_query",
                    queryError == null ? question : failureAuditSummary(queryError), "",
                    Status.FAILED, System.currentTimeMillis() - started, error);
            auditService.writeMain(requestId, openId, chatId, null,
                    queryError == null ? List.of() : queryError.projectsQueried(), question,
                    "ai_unavailable", answer, System.currentTimeMillis() - started, error);
            chainTraceService.save(requestId, failureTrace(requestId, error,
                    System.currentTimeMillis() - started, queryError));
            writeTraceStatus(requestId);
            auditService.updateProcessingLatency(requestId, System.currentTimeMillis() - started);
            return ProcessResult.failed("AI 服务暂不可用");
        }
    }

    public record ProcessResult(boolean succeeded, String error, boolean partial,
                                boolean pending, Duration resumeAfter) {
        public static ProcessResult success() { return new ProcessResult(true, null, false, false, null); }
        public static ProcessResult partial(String reason) {
            return new ProcessResult(true, reason, true, false, null);
        }
        public static ProcessResult pending(Duration resumeAfter) {
            return new ProcessResult(false, null, false, true, resumeAfter);
        }
        public static ProcessResult failed(String error) {
            return new ProcessResult(false, error, false, false, null);
        }
    }

    private void writeTraceStatus(String requestId) {
        boolean incomplete = chainTraceService.eventTraceIncomplete(requestId);
        auditService.writeTraceStatus(requestId, incomplete ? "PARTIAL" : "COMPLETE",
                incomplete ? "部分追踪事件写入失败" : null);
    }

    private String auditSummary(UnifiedQueryService.QueryResult result) {
        return "historyTurns=" + result.historyTurns()
                + ", toolRounds=" + result.toolRounds()
                + ", logicalToolCalls=" + result.logicalToolCalls()
                + ", physicalMcpCalls=" + result.physicalMcpCalls()
                + ", pages=" + result.pages()
                + ", chunks=" + result.chunks()
                + ", cacheHits=" + result.cacheHits()
                + ", savedMcpCalls=" + result.cacheHits()
                + ", projects=" + result.projectsQueried()
                + ", partialFailure=" + !result.failures().isEmpty()
                + ", inputTokens=" + result.inputTokens()
                + ", outputTokens=" + result.outputTokens()
                + ", stopReason=" + result.stopReason();
    }

    private String failureAuditSummary(UnifiedQueryService.QueryExecutionException error) {
        return "historyTurns=" + error.historyTurns()
                + ", toolRounds=" + error.toolRounds()
                + ", logicalToolCalls=" + error.logicalToolCalls()
                + ", physicalMcpCalls=" + error.physicalMcpCalls()
                + ", pages=" + error.pages()
                + ", chunks=" + error.chunks()
                + ", projects=" + error.projectsQueried()
                + ", partialFailure=" + !error.failures().isEmpty()
                + ", inputTokens=" + error.inputTokens()
                + ", outputTokens=" + error.outputTokens()
                + ", stopReason=deepseek_unavailable";
    }

    private ChainTrace failureTrace(String requestId, String error, long latencyMs,
                                    UnifiedQueryService.QueryExecutionException queryError) {
        ChainTrace trace = new ChainTrace(requestId);
        String previous = null;
        if (queryError != null) {
            int index = 0;
            for (String tool : queryError.toolsUsed()) {
                String id = "mcp_tool_" + (++index);
                trace.addNode(new ChainTrace.Node(id, "mcp_call", tool, Status.SUCCEEDED, 0,
                        Map.of("toolName", tool)));
                if (previous != null) trace.addEdge(new ChainTrace.Edge(previous, id));
                previous = id;
            }
        }
        trace.addNode(new ChainTrace.Node("deepseek_failure", "model_call", "DeepSeek 服务异常",
                Status.FAILED, latencyMs, Map.of("error", error)));
        if (previous != null) trace.addEdge(new ChainTrace.Edge(previous, "deepseek_failure"));
        trace.summary.path = "ai_unavailable";
        trace.summary.model = queryError == null ? "deepseek" : queryError.model();
        trace.summary.totalLatencyMs = latencyMs;
        trace.summary.failures = queryError == null ? List.of(error) : mergeFailures(queryError.failures(), error);
        trace.summary.stopReason = "deepseek_unavailable";
        if (queryError != null) {
            trace.summary.toolRounds = queryError.toolRounds();
            trace.summary.logicalToolCalls = queryError.logicalToolCalls();
            trace.summary.totalMcpCalls = queryError.physicalMcpCalls();
            trace.summary.totalPages = queryError.pages();
            trace.summary.totalChunks = queryError.chunks();
            trace.summary.historyTurns = queryError.historyTurns();
            trace.summary.inputTokens = queryError.inputTokens();
            trace.summary.outputTokens = queryError.outputTokens();
            trace.summary.toolsUsed = queryError.toolsUsed();
            trace.summary.projectsQueried = queryError.projectsQueried();
        }
        return trace;
    }

    private List<String> mergeFailures(List<String> failures, String error) {
        List<String> merged = new ArrayList<>(failures);
        if (!merged.contains(error)) merged.add(error);
        return List.copyOf(merged);
    }

    private ChainTrace traceFor(String requestId, UnifiedQueryService.QueryResult result) {
        ChainTrace trace = new ChainTrace(requestId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", result.path());
        metadata.put("modelName", result.model());
        metadata.put("toolRounds", result.toolRounds());
        metadata.put("logicalToolCalls", result.logicalToolCalls());
        metadata.put("inputTokens", result.inputTokens());
        metadata.put("outputTokens", result.outputTokens());
        metadata.put("stopReason", result.stopReason());
        trace.addNode(new ChainTrace.Node("deepseek_decision", "model_call", "DeepSeek 统一决策与分析",
                Status.SUCCEEDED, result.latencyMs(), metadata));
        String previous = "deepseek_decision";
        int index = 0;
        for (String tool : result.toolsUsed()) {
            String id = "mcp_tool_" + (++index);
            trace.addNode(new ChainTrace.Node(id, "mcp_call", tool,
                    result.failures().isEmpty() ? Status.SUCCEEDED : "PARTIAL", 0,
                    Map.of("toolName", tool)));
            trace.addEdge(new ChainTrace.Edge(previous, id));
            previous = id;
        }
        if (result.chunks() > 0) {
            trace.addNode(new ChainTrace.Node("chunk_analysis", "model_call", "DeepSeek 分块分析",
                    result.failures().isEmpty() ? Status.SUCCEEDED : "PARTIAL", 0,
                    Map.of("chunkCount", result.chunks())));
            trace.addEdge(new ChainTrace.Edge(previous, "chunk_analysis"));
            previous = "chunk_analysis";
        }
        trace.addNode(new ChainTrace.Node("final_answer", "model_call", "DeepSeek 最终回答",
                Status.SUCCEEDED, 0, Map.of("answer", result.answer())));
        trace.addEdge(new ChainTrace.Edge(previous, "final_answer"));
        trace.summary.path = result.path();
        trace.summary.model = result.model();
        trace.summary.toolRounds = result.toolRounds();
        trace.summary.logicalToolCalls = result.logicalToolCalls();
        trace.summary.totalMcpCalls = result.physicalMcpCalls();
        trace.summary.totalPages = result.pages();
        trace.summary.totalChunks = result.chunks();
        trace.summary.historyTurns = result.historyTurns();
        trace.summary.inputTokens = result.inputTokens();
        trace.summary.outputTokens = result.outputTokens();
        trace.summary.cacheHits = result.cacheHits();
        trace.summary.savedMcpCalls = result.cacheHits();
        trace.summary.stopReason = result.stopReason();
        trace.summary.toolsUsed = result.toolsUsed();
        trace.summary.projectsQueried = result.projectsQueried();
        trace.summary.failures = result.failures();
        trace.summary.totalLatencyMs = result.latencyMs();
        return trace;
    }

    private void processMcpConfigStatus(String requestId, String question, String messageId, String chatId,
                                        String openId, String chatType, long started) {
        TokenResolver.McpConfigCheckResult check = tokenResolver.checkMcpConfig(openId, chatId, chatType, maxProjects);
        if (!check.configured()) {
            feishuClient.replyMcpRequiredCard(messageId, check);
            auditService.writeMain(requestId, openId, chatId, null, List.of(), question,
                    "mcp_config_status", check.reason(), System.currentTimeMillis() - started, check.reason());
            return;
        }
        TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(openId, chatId, chatType, maxProjects);
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        if (!context.hasError()) {
            for (TokenResolver.TokenEntry token : context.tokens()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("projectId", token.projectId());
                item.put("projectName", token.projectName());
                try {
                    item.put("toolCount", extractTools(mcpAdapter.listTools(token.token())).size());
                    item.put("ok", true);
                    passed++;
                } catch (Exception e) {
                    item.put("ok", false);
                    item.put("error", readable(e));
                }
                results.add(item);
            }
        }
        String answer = "MCP 配置已绑定。实时验证：" + passed + "/" + results.size() + " 个项目通过。";
        feishuClient.replyAnswerCard(messageId, question, answer, "MCP 配置状态",
                passed == results.size() ? "green" : "orange");
        auditService.writeMain(requestId, openId, chatId, check.primelayerUserId(),
                results.stream().map(item -> String.valueOf(item.get("projectId"))).toList(),
                question, "mcp_config_status", answer, System.currentTimeMillis() - started,
                passed == results.size() ? null : "部分 MCP 项目验证失败");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }

    private String configurationHint() {
        return "MCP 配置由管理员在后台「人员配置」中维护。请确认人员或群已绑定项目，并且项目 Token 验证通过。请勿在飞书消息中发送 Token 明文。";
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        return null;
    }

    private String readable(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
