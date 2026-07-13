package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import com.larkconnect.agent.mcp.McpQueryGateway;
import com.larkconnect.agent.mcp.McpToolDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class UnifiedQueryService {
    static final int MAX_TOOL_ROUNDS = 8;
    static final int MAX_TOOL_CALLS_PER_ROUND = 8;
    static final int MAX_TOTAL_TOOL_CALLS = 32;
    static final int MAX_CONCURRENCY = 4;
    static final int MAX_CHUNK_CHARS = 32_000;

    private final DeepSeekConversationClient deepSeek;
    private final McpQueryGateway mcpGateway;
    private final McpToolDefinitionMapper toolMapper;
    private final ConversationHistoryProvider historyProvider;
    private final ObjectMapper objectMapper;

    public UnifiedQueryService(DeepSeekConversationClient deepSeek, McpQueryGateway mcpGateway,
                               McpToolDefinitionMapper toolMapper, ConversationHistoryProvider historyProvider,
                               ObjectMapper objectMapper) {
        this.deepSeek = deepSeek;
        this.mcpGateway = mcpGateway;
        this.toolMapper = toolMapper;
        this.historyProvider = historyProvider;
        this.objectMapper = objectMapper;
    }

    public QueryResult query(QueryRequest request) {
        long started = System.currentTimeMillis();
        String selectedModel = deepSeek.model();
        McpQueryGateway.QueryContext context = mcpGateway.loadContext(request.openId(), request.chatId(), request.chatType());
        McpToolDefinitionMapper.MappedTools mapped = toolMapper.map(context.availableTools());
        List<ConversationHistoryService.HistoryTurn> history = historyProvider.load(
                request.chatType(), request.openId(), request.chatId(), request.requestId());
        List<Map<String, Object>> messages = initialMessages(request, context, history);

        int toolRounds = 0;
        int logicalCalls = 0;
        int physicalCalls = 0;
        int pages = 0;
        int chunks = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        int successfulObservations = 0;
        String stopReason = null;
        String answer = null;
        Set<String> toolsUsed = new LinkedHashSet<>();
        Set<String> projectsQueried = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();

        while (toolRounds < MAX_TOOL_ROUNDS && logicalCalls < MAX_TOTAL_TOOL_CALLS) {
            DeepSeekConversationClient.Completion completion;
            try {
                completion = deepSeek.complete(selectedModel, messages, mapped.deepSeekTools(), true);
            } catch (Exception e) {
                throw queryFailure(e, selectedModel, toolRounds, logicalCalls, physicalCalls, pages, chunks,
                        history.size(), inputTokens, outputTokens, toolsUsed, projectsQueried, failures);
            }
            inputTokens += completion.inputTokens();
            outputTokens += completion.outputTokens();
            List<DeepSeekConversationClient.ToolCall> requested = completion.toolCalls() == null ? List.of() : completion.toolCalls();
            if (requested.isEmpty()) {
                answer = completion.content();
                break;
            }

            messages.add(completion.assistantMessage());
            toolRounds++;
            int remaining = MAX_TOTAL_TOOL_CALLS - logicalCalls;
            int acceptedCount = Math.min(Math.min(requested.size(), MAX_TOOL_CALLS_PER_ROUND), remaining);
            List<DeepSeekConversationClient.ToolCall> accepted = requested.subList(0, acceptedCount);
            if (acceptedCount < requested.size()) {
                stopReason = requested.size() > MAX_TOOL_CALLS_PER_ROUND ? "max_calls_per_round" : "max_total_tool_calls";
                failures.add("DeepSeek 请求的工具数量超过安全上限，未执行其余 " + (requested.size() - acceptedCount) + " 个调用");
                for (DeepSeekConversationClient.ToolCall rejected : requested.subList(acceptedCount, requested.size())) {
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", rejected.id(),
                            "content", "{\"error\":\"未执行：工具调用超过安全上限\"}"
                    ));
                }
            }
            logicalCalls += accepted.size();

            List<ExecutedCall> executions = executeConcurrently(request.requestId(), context, mapped, accepted);
            for (ExecutedCall execution : executions) {
                toolsUsed.add(execution.originalToolName());
                failures.addAll(execution.failures());
                for (McpQueryGateway.ToolObservation observation : execution.observations()) {
                    physicalCalls += observation.physicalCalls();
                    pages += observation.pages();
                    projectsQueried.add(observation.projectId());
                    if (observation.succeeded()) successfulObservations++;
                    if (observation.error() != null && !observation.error().isBlank()) failures.add(observation.error());
                    if (observation.truncated()) {
                        failures.add("项目 " + observation.projectName() + " / " + observation.toolName()
                                + " 自动分页达到 50 页安全上限，结果可能不完整");
                    }
                }
                CompactedObservation compacted = compact(selectedModel, execution.originalToolName(), execution.observations(), failures);
                chunks += compacted.chunks();
                inputTokens += compacted.inputTokens();
                outputTokens += compacted.outputTokens();
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", execution.call().id(),
                        "content", compacted.content()
                ));
            }
            if (toolRounds >= MAX_TOOL_ROUNDS) stopReason = "max_tool_rounds";
            if (logicalCalls >= MAX_TOTAL_TOOL_CALLS) stopReason = "max_total_tool_calls";
            if (stopReason != null) break;
        }

        if (answer == null || answer.isBlank()) {
            messages.add(Map.of("role", "system", "content", finalizationInstruction(stopReason, failures)));
            DeepSeekConversationClient.Completion finalCompletion;
            try {
                finalCompletion = deepSeek.complete(selectedModel, messages, List.of(), false);
            } catch (Exception e) {
                throw queryFailure(e, selectedModel, toolRounds, logicalCalls, physicalCalls, pages, chunks,
                        history.size(), inputTokens, outputTokens, toolsUsed, projectsQueried, failures);
            }
            inputTokens += finalCompletion.inputTokens();
            outputTokens += finalCompletion.outputTokens();
            answer = finalCompletion.content();
        }

        String path = logicalCalls == 0 ? "direct_deepseek" : "mcp_deepseek";
        if (logicalCalls > 0 && successfulObservations == 0) {
            answer = "项目数据暂不可用，本次 MCP 查询没有取得可用于分析的成功结果。" + failureSuffix(failures);
        } else if (!failures.isEmpty()) {
            answer = safe(answer) + "\n\n数据缺口：" + String.join("；", distinct(failures));
        }
        if ("max_tool_rounds".equals(stopReason)) {
            answer = safe(answer) + "\n\n说明：已达到 MCP 查询轮次上限（8 轮），以上结论仅基于已成功取得的数据。";
        } else if (stopReason != null) {
            answer = safe(answer) + "\n\n说明：查询因 " + stopReason + " 安全上限停止，以上结论仅基于已成功取得的数据。";
        }

        return new QueryResult(path, safe(answer), selectedModel, toolRounds, logicalCalls, physicalCalls,
                pages, chunks, history.size(), List.copyOf(toolsUsed), List.copyOf(projectsQueried),
                distinct(failures), stopReason, inputTokens, outputTokens, System.currentTimeMillis() - started);
    }

    private List<Map<String, Object>> initialMessages(QueryRequest request, McpQueryGateway.QueryContext context,
                                                       List<ConversationHistoryService.HistoryTurn> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(context, request.chatType())));
        for (ConversationHistoryService.HistoryTurn turn : history) {
            messages.add(Map.of("role", "user", "content", turn.question()));
            messages.add(Map.of("role", "assistant", "content", turn.answer()));
        }
        messages.add(Map.of("role", "user", "content", request.question()));
        return messages;
    }

    private String systemPrompt(McpQueryGateway.QueryContext context, String chatType) {
        String projects = context.projects().stream()
                .map(project -> project.projectId() + "=" + project.projectName())
                .reduce((a, b) -> a + "，" + b).orElse("无");
        String availability = context.availabilityError() == null ? "MCP 可用"
                : context.availableTools().isEmpty() ? "MCP 不可用：" + context.availabilityError()
                : "MCP 部分可用：" + context.availabilityError();
        return """
                你是 Lark Connect 的统一问答与数据分析助手。当前会话类型：%s。可访问项目：%s。%s。
                判断问题是否需要项目实时数据：需要时必须调用提供的 MCP 工具，绝不能凭常识编造项目事实；不需要时直接回答。
                工具参数 projectIds 必须来自可访问项目。私聊有多个项目且当前问题与历史均不能确定项目时，先直接追问项目，不调用工具。
                可以在一轮调用多个互补工具，也可以根据工具结果继续查询。部分失败必须在最终答案中说明范围、失败项和局限。
                没有外部工具时，不得声称掌握天气、新闻或其他实时外部数据。
                """.formatted("group".equals(chatType) ? "群聊" : "私聊", projects, availability);
    }

    private List<ExecutedCall> executeConcurrently(String requestId, McpQueryGateway.QueryContext context,
                                                    McpToolDefinitionMapper.MappedTools mapped,
                                                    List<DeepSeekConversationClient.ToolCall> calls) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENCY, Math.max(1, calls.size())));
        try {
            List<Future<ExecutedCall>> futures = new ArrayList<>();
            for (DeepSeekConversationClient.ToolCall call : calls) {
                Callable<ExecutedCall> task = () -> executeOne(requestId, context, mapped, call);
                futures.add(executor.submit(task));
            }
            List<ExecutedCall> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Future<ExecutedCall> future = futures.get(i);
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    DeepSeekConversationClient.ToolCall call = calls.get(i);
                    results.add(new ExecutedCall(call, call.name(), List.of(), List.of(readable(e))));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private ExecutedCall executeOne(String requestId, McpQueryGateway.QueryContext context,
                                    McpToolDefinitionMapper.MappedTools mapped,
                                    DeepSeekConversationClient.ToolCall call) {
        try {
            String original = mapped.originalName(call.name());
            List<String> projectIds = stringList(call.input().get("projectIds"));
            Map<String, Object> arguments = objectMap(call.input().get("arguments"));
            List<McpQueryGateway.ToolObservation> observations = mcpGateway.execute(
                    requestId, context, original, projectIds, arguments);
            return new ExecutedCall(call, original, observations, List.of());
        } catch (Exception e) {
            return new ExecutedCall(call, call.name(), List.of(), List.of(readable(e)));
        }
    }

    private CompactedObservation compact(String selectedModel, String toolName, List<McpQueryGateway.ToolObservation> observations,
                                          List<String> failures) {
        try {
            String json = objectMapper.writeValueAsString(observations);
            if (json.length() <= MAX_CHUNK_CHARS) return new CompactedObservation(json, 0, 0, 0);
            List<RawChunk> rawChunks = splitByPageBoundaries(observations);
            List<Map<String, Object>> summaries = new ArrayList<>();
            boolean allChunksSucceeded = true;
            int inputTokens = 0;
            int outputTokens = 0;
            for (int i = 0; i < rawChunks.size(); i++) {
                RawChunk rawChunk = rawChunks.get(i);
                try {
                    DeepSeekConversationClient.ChunkAnalysis analysis = deepSeek.analyzeChunkWithUsage(
                            selectedModel, toolName, rawChunk.projectId(), rawChunk.content(), i + 1, rawChunks.size());
                    inputTokens += analysis.inputTokens();
                    outputTokens += analysis.outputTokens();
                    summaries.add(Map.of("chunk", i + 1, "projectId", rawChunk.projectId(), "summary", analysis.content()));
                } catch (Exception e) {
                    allChunksSucceeded = false;
                    String failure = "分块 " + (i + 1) + "/" + rawChunks.size() + " 分析失败：" + readable(e);
                    failures.add(failure);
                    summaries.add(Map.of("chunk", i + 1, "projectId", rawChunk.projectId(), "error", failure));
                }
            }
            return new CompactedObservation(objectMapper.writeValueAsString(Map.of(
                    "largeResult", true,
                    "allChunksProcessed", allChunksSucceeded,
                    "chunkCount", rawChunks.size(),
                    "summaries", summaries
            )), rawChunks.size(), inputTokens, outputTokens);
        } catch (Exception e) {
            failures.add("工具结果序列化失败：" + readable(e));
            return new CompactedObservation("{\"error\":\"工具结果无法序列化\"}", 0, 0, 0);
        }
    }

    private List<RawChunk> splitByPageBoundaries(List<McpQueryGateway.ToolObservation> observations) throws Exception {
        List<RawChunk> units = new ArrayList<>();
        for (McpQueryGateway.ToolObservation observation : observations) {
            Object pages = observation.payload().get("pages");
            if (pages instanceof List<?> list && !list.isEmpty()) {
                for (int i = 0; i < list.size(); i++) {
                    Map<String, Object> unit = new LinkedHashMap<>();
                    unit.put("projectId", observation.projectId());
                    unit.put("toolName", observation.toolName());
                    unit.put("pageIndex", i + 1);
                    unit.put("fetchedPageCount", list.size());
                    unit.put("totalCount", observation.payload().get("totalCount"));
                    unit.put("pageLimitReached", observation.payload().get("pageLimitReached"));
                    unit.put("page", list.get(i));
                    units.add(new RawChunk(observation.projectId(), objectMapper.writeValueAsString(unit)));
                }
            } else {
                units.add(new RawChunk(observation.projectId(), objectMapper.writeValueAsString(observation)));
            }
        }
        List<RawChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentProject = null;
        for (RawChunk unit : units) {
            if (currentProject != null && !currentProject.equals(unit.projectId()) && !current.isEmpty()) {
                chunks.add(new RawChunk(currentProject, current.toString()));
                current.setLength(0);
            }
            currentProject = unit.projectId();
            if (unit.content().length() > MAX_CHUNK_CHARS) {
                if (!current.isEmpty()) {
                    chunks.add(new RawChunk(currentProject, current.toString()));
                    current.setLength(0);
                }
                // A single page is kept intact so JSON and record boundaries are never cut arbitrarily.
                chunks.add(unit);
            } else if (current.length() + unit.content().length() + 1 > MAX_CHUNK_CHARS) {
                chunks.add(new RawChunk(currentProject, current.toString()));
                current.setLength(0);
                current.append(unit.content());
            } else {
                if (!current.isEmpty()) current.append('\n');
                current.append(unit.content());
            }
        }
        if (!current.isEmpty()) chunks.add(new RawChunk(currentProject, current.toString()));
        return chunks.isEmpty() ? List.of(new RawChunk("unknown", "[]")) : chunks;
    }

    private String finalizationInstruction(String stopReason, List<String> failures) {
        return "停止调用工具，立即基于已经成功取得的数据给出最终答案。"
                + (stopReason == null ? "" : "停止原因：" + stopReason + "。")
                + (failures.isEmpty() ? "" : "必须披露这些数据缺口：" + String.join("；", distinct(failures)));
    }

    private String failureSuffix(List<String> failures) {
        return failures.isEmpty() ? "" : "\n\n失败详情：" + String.join("；", distinct(failures));
    }

    private String readable(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "AI 服务未返回有效答案。" : value;
    }

    private QueryExecutionException queryFailure(Exception cause, String model, int toolRounds, int logicalCalls,
                                                  int physicalCalls, int pages, int chunks, int historyTurns,
                                                  int inputTokens, int outputTokens, Set<String> toolsUsed,
                                                  Set<String> projectsQueried, List<String> failures) {
        return new QueryExecutionException(readable(cause), cause, model, toolRounds, logicalCalls, physicalCalls,
                pages, chunks, historyTurns, inputTokens, outputTokens, List.copyOf(toolsUsed),
                List.copyOf(projectsQueried), distinct(failures));
    }

    private record ExecutedCall(DeepSeekConversationClient.ToolCall call, String originalToolName,
                                List<McpQueryGateway.ToolObservation> observations, List<String> failures) {}
    private record CompactedObservation(String content, int chunks, int inputTokens, int outputTokens) {}
    private record RawChunk(String projectId, String content) {}

    public record QueryRequest(String requestId, String question, String chatType, String openId, String chatId) {}
    public record QueryResult(String path, String answer, String model, int toolRounds, int logicalToolCalls,
                              int physicalMcpCalls, int pages, int chunks, int historyTurns,
                              List<String> toolsUsed, List<String> projectsQueried, List<String> failures,
                              String stopReason, int inputTokens, int outputTokens, long latencyMs) {}

    public static final class QueryExecutionException extends RuntimeException {
        private final String model;
        private final int toolRounds;
        private final int logicalToolCalls;
        private final int physicalMcpCalls;
        private final int pages;
        private final int chunks;
        private final int historyTurns;
        private final int inputTokens;
        private final int outputTokens;
        private final List<String> toolsUsed;
        private final List<String> projectsQueried;
        private final List<String> failures;

        QueryExecutionException(String message, Throwable cause, String model, int toolRounds, int logicalToolCalls,
                                int physicalMcpCalls, int pages, int chunks, int historyTurns, int inputTokens,
                                int outputTokens, List<String> toolsUsed, List<String> projectsQueried,
                                List<String> failures) {
            super(message, cause);
            this.model = model;
            this.toolRounds = toolRounds;
            this.logicalToolCalls = logicalToolCalls;
            this.physicalMcpCalls = physicalMcpCalls;
            this.pages = pages;
            this.chunks = chunks;
            this.historyTurns = historyTurns;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.toolsUsed = toolsUsed;
            this.projectsQueried = projectsQueried;
            this.failures = failures;
        }

        public String model() { return model; }
        public int toolRounds() { return toolRounds; }
        public int logicalToolCalls() { return logicalToolCalls; }
        public int physicalMcpCalls() { return physicalMcpCalls; }
        public int pages() { return pages; }
        public int chunks() { return chunks; }
        public int historyTurns() { return historyTurns; }
        public int inputTokens() { return inputTokens; }
        public int outputTokens() { return outputTokens; }
        public List<String> toolsUsed() { return toolsUsed; }
        public List<String> projectsQueried() { return projectsQueried; }
        public List<String> failures() { return failures; }
    }
}
