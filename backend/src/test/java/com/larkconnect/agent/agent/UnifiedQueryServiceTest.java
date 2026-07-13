package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import com.larkconnect.agent.mcp.McpQueryGateway;
import com.larkconnect.agent.mcp.McpToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnifiedQueryServiceTest {
    @Test
    void returnsDirectPathWhenDeepSeekAnswersWithoutTools() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("你好", List.of()));
        FakeGateway gateway = new FakeGateway(context());
        UnifiedQueryService service = service(deepSeek, gateway);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest("r1", "介绍一下你自己", "p2p", "u1", "c1"));

        assertThat(result.path()).isEqualTo("direct_deepseek");
        assertThat(result.answer()).isEqualTo("你好");
        assertThat(result.toolRounds()).isZero();
        assertThat(gateway.executions.get()).isZero();
        assertThat(deepSeek.receivedMessages.get(0).get(0).get("content").toString())
                .contains("当前会话类型：私聊");
    }

    @Test
    void executesMultipleToolsAndReturnsMcpPath() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t1", "mcp_query_tasks"), tool("t2", "mcp_get_report"))),
                completion("分析完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        UnifiedQueryService service = service(deepSeek, gateway);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest("r2", "分析项目", "p2p", "u1", "c1"));

        assertThat(result.path()).isEqualTo("mcp_deepseek");
        assertThat(result.answer()).isEqualTo("分析完成");
        assertThat(result.logicalToolCalls()).isEqualTo(2);
        assertThat(result.toolRounds()).isEqualTo(1);
        assertThat(gateway.executions.get()).isEqualTo(2);
        assertThat(result.model()).isEqualTo("deepseek-v4-pro");
        assertThat(deepSeek.selectedModels).containsOnly("deepseek-v4-pro");
        assertThat(deepSeek.modelReads).isEqualTo(1);
    }

    @Test
    void stopsAtEightToolRoundsAndForcesFinalAnswerWithoutTools() {
        List<DeepSeekConversationClient.Completion> completions = new ArrayList<>();
        for (int i = 0; i < 8; i++) completions.add(completion(null, List.of(tool("t" + i, "mcp_query_tasks"))));
        completions.add(completion("基于已有数据收敛", List.of()));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completions.toArray(DeepSeekConversationClient.Completion[]::new));
        UnifiedQueryService service = service(deepSeek, new FakeGateway(context()));

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest("r3", "复杂查询", "p2p", "u1", "c1"));

        assertThat(result.toolRounds()).isEqualTo(8);
        assertThat(result.stopReason()).isEqualTo("max_tool_rounds");
        assertThat(result.answer()).contains("基于已有数据收敛").contains("已达到 MCP 查询轮次上限");
        assertThat(deepSeek.allowTools).endsWith(false);
    }

    @Test
    void exposesPartialFailureInFinalAnswer() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t1", "mcp_query_tasks"))),
                completion("成功项目结论", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.partial = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r4", "跨项目分析", "p2p", "u1", "c1"));

        assertThat(result.answer()).contains("成功项目结论").contains("数据缺口").contains("P2 查询失败");
        assertThat(result.failures()).contains("P2 查询失败");
    }

    @Test
    void analyzesEveryChunkOfLargePaginatedResultWithoutSilentTruncation() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t1", "mcp_query_tasks"))),
                completion("大数据分析完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.large = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r5", "分析大量数据", "p2p", "u1", "c1"));

        assertThat(result.chunks()).isGreaterThan(1);
        assertThat(deepSeek.analyzedChunks).hasSize(result.chunks());
        assertThat(String.join("", deepSeek.analyzedChunks)).contains("TAIL_MARKER");
        assertThat(result.inputTokens()).isEqualTo(26);
        assertThat(result.outputTokens()).isEqualTo(14);
    }

    @Test
    void acknowledgesEveryRequestedToolCallWhenPerRoundLimitIsExceeded() {
        List<DeepSeekConversationClient.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < 9; i++) calls.add(tool("limit-" + i, "mcp_query_tasks"));
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, calls),
                completion("基于已执行调用生成结论", List.of())
        );

        UnifiedQueryService.QueryResult result = service(deepSeek, new FakeGateway(context())).query(
                new UnifiedQueryService.QueryRequest("r6", "触发单轮上限", "p2p", "u1", "c1"));

        assertThat(result.logicalToolCalls()).isEqualTo(8);
        assertThat(result.stopReason()).isEqualTo("max_calls_per_round");
        assertThat(deepSeek.receivedMessages.get(1).stream()
                .filter(message -> "tool".equals(message.get("role"))))
                .hasSize(9);
    }

    @Test
    void disclosesWhenAutomaticPaginationReachesFiftyPageLimit() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-limit", "mcp_query_tasks"))),
                completion("基于已获取分页的分析", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.truncated = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r7", "分页上限", "p2p", "u1", "c1"));

        assertThat(result.answer()).contains("50 页").contains("数据缺口");
        assertThat(result.failures()).anyMatch(failure -> failure.contains("50 页"));
    }

    @Test
    void marksLargeResultAsNotFullyAnalyzedWhenAnyChunkFails() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-chunk", "mcp_query_tasks"))),
                completion("基于成功分块的结论", List.of())
        );
        deepSeek.failedChunk = 2;
        FakeGateway gateway = new FakeGateway(context());
        gateway.large = true;

        service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r8", "分块失败", "p2p", "u1", "c1"));

        String toolContent = deepSeek.receivedMessages.get(1).stream()
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst().orElseThrow();
        assertThat(toolContent).contains("\"allChunksProcessed\":false");
    }

    @Test
    void preservesProjectAttributionForCrossProjectChunks() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-projects", "mcp_query_tasks"))),
                completion("跨项目结论", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.large = true;
        gateway.multiProjectLarge = true;

        service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r9", "跨项目大数据", "p2p", "u1", "c1"));

        assertThat(deepSeek.analyzedProjects).contains("P1", "P2");
    }

    @Test
    void preservesCompletedMetricsWhenALaterDeepSeekRoundFails() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-before-failure", "mcp_query_tasks")))
        );

        assertThatThrownBy(() -> service(deepSeek, new FakeGateway(context())).query(
                new UnifiedQueryService.QueryRequest("r10", "后续轮失败", "p2p", "u1", "c1")))
                .isInstanceOf(UnifiedQueryService.QueryExecutionException.class)
                .satisfies(error -> {
                    UnifiedQueryService.QueryExecutionException queryError =
                            (UnifiedQueryService.QueryExecutionException) error;
                    assertThat(queryError.model()).isEqualTo("deepseek-v4-pro");
                    assertThat(queryError.toolRounds()).isEqualTo(1);
                    assertThat(queryError.logicalToolCalls()).isEqualTo(1);
                    assertThat(queryError.physicalMcpCalls()).isEqualTo(1);
                    assertThat(queryError.pages()).isEqualTo(1);
                    assertThat(queryError.inputTokens()).isEqualTo(10);
                    assertThat(queryError.toolsUsed()).containsExactly("query_tasks");
                });
    }

    private UnifiedQueryService service(FakeDeepSeek deepSeek, FakeGateway gateway) {
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        return new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(), history, new ObjectMapper());
    }

    private static McpQueryGateway.QueryContext context() {
        return new McpQueryGateway.QueryContext("pl1",
                List.of(new McpQueryGateway.Project("P1", "项目一"), new McpQueryGateway.Project("P2", "项目二")),
                List.of(
                        Map.of("name", "query_tasks", "description", "tasks", "inputSchema", Map.of("type", "object")),
                        Map.of("name", "get_report", "description", "report", "inputSchema", Map.of("type", "object"))
                ), null);
    }

    private static DeepSeekConversationClient.ToolCall tool(String id, String name) {
        return new DeepSeekConversationClient.ToolCall(id, name, Map.of("projectIds", List.of("P1"), "arguments", Map.of()));
    }

    private static DeepSeekConversationClient.Completion completion(String content, List<DeepSeekConversationClient.ToolCall> calls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        return new DeepSeekConversationClient.Completion(content, calls, message, 10, 5);
    }

    private static class FakeDeepSeek implements DeepSeekConversationClient {
        private final Deque<Completion> completions = new ArrayDeque<>();
        private final List<Boolean> allowTools = new ArrayList<>();
        private final List<String> analyzedChunks = new ArrayList<>();
        private final List<String> analyzedProjects = new ArrayList<>();
        private final List<List<Map<String, Object>>> receivedMessages = new ArrayList<>();
        private final List<String> selectedModels = new ArrayList<>();
        private int failedChunk = -1;
        private int modelReads;

        FakeDeepSeek(Completion... values) { completions.addAll(List.of(values)); }

        @Override
        public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean allowTools) {
            this.allowTools.add(allowTools);
            this.receivedMessages.add(List.copyOf(messages));
            return completions.removeFirst();
        }

        @Override
        public Completion complete(String selectedModel, List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools, boolean allowTools) {
            selectedModels.add(selectedModel);
            return complete(messages, tools, allowTools);
        }

        @Override public String analyzeChunk(String toolName, String projectId, String json, int chunkIndex, int chunkCount) {
            analyzedChunks.add(json);
            analyzedProjects.add(projectId);
            if (chunkIndex == failedChunk) throw new IllegalStateException("chunk unavailable");
            return "chunk-" + chunkIndex;
        }
        @Override
        public ChunkAnalysis analyzeChunkWithUsage(String selectedModel, String toolName, String projectId,
                                                   String json, int chunkIndex, int chunkCount) {
            selectedModels.add(selectedModel);
            return new ChunkAnalysis(analyzeChunk(toolName, projectId, json, chunkIndex, chunkCount), 3, 2);
        }
        @Override public String model() { modelReads++; return modelReads == 1 ? "deepseek-v4-pro" : "deepseek-v4-flash"; }
    }

    private static class FakeGateway implements McpQueryGateway {
        private final QueryContext context;
        final AtomicInteger executions = new AtomicInteger();
        boolean partial;
        boolean large;
        boolean truncated;
        boolean multiProjectLarge;

        FakeGateway(QueryContext context) { this.context = context; }
        @Override public QueryContext loadContext(String openId, String chatId, String chatType) { return context; }

        @Override
        public List<ToolObservation> execute(String requestId, QueryContext context, String toolName, List<String> projectIds, Map<String, Object> arguments) {
            executions.incrementAndGet();
            List<ToolObservation> result = new ArrayList<>();
            Map<String, Object> payload = large
                    ? Map.of("pages", List.of(Map.of("data", "x".repeat(20_000)), Map.of("data", "y".repeat(20_000) + "TAIL_MARKER")))
                    : Map.of("data", List.of(1, 2));
            result.add(new ToolObservation("P1", "项目一", toolName, "SUCCEEDED", payload, null, 1, large ? 2 : 1, truncated));
            if (multiProjectLarge) result.add(new ToolObservation("P2", "项目二", toolName, "SUCCEEDED",
                    Map.of("pages", List.of(Map.of("data", "z".repeat(20_000)))), null, 1, 1, false));
            if (partial) result.add(new ToolObservation("P2", "项目二", toolName, "FAILED", Map.of(), "P2 查询失败", 1, 0, false));
            return result;
        }
    }
}
