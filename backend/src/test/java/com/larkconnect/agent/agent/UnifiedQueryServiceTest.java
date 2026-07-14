package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import com.larkconnect.agent.mcp.McpQueryGateway;
import com.larkconnect.agent.mcp.McpToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnifiedQueryServiceTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void returnsDirectPathWhenDeepSeekAnswersWithoutTools() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("你好", List.of()));
        FakeGateway gateway = new FakeGateway(context());
        UnifiedQueryService service = service(deepSeek, gateway);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest("r1", "介绍一下你自己", "p2p", "u1", "c1"));

        assertThat(result.path()).isEqualTo("direct_deepseek");
        assertThat(result.answer()).isEqualTo("你好");
        assertThat(result.presentation().blocks().get(0).markdown()).isEqualTo("你好");
        assertThat(result.presentationJson()).contains("\"plainText\":\"你好\"");
        assertThat(deepSeek.presentationCalls).isEqualTo(1);
        assertThat(result.toolRounds()).isZero();
        assertThat(gateway.executions.get()).isZero();
        assertThat(deepSeek.receivedMessages.get(0).get(0).get("content").toString())
                .contains("当前会话类型：私聊")
                .contains("不得重复请求相同工具、项目和参数")
                .contains("仅当分页、日期校验或分块分析不完整时披露覆盖率和缺口");
    }

    @Test
    void resumesFromPersistedCompletedRoundMessagesAfterRestart() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        String state = mapper.writeValueAsString(Map.of("messages", List.of(
                Map.of("role", "system", "content", "RESTORED_LEDGER"),
                Map.of("role", "user", "content", "原问题"),
                Map.of("role", "tool", "tool_call_id", "old", "content", "{\"coverageComplete\":true}"))));
        when(checkpoints.load("r-resume")).thenReturn(Optional.of(new QuerySession("r-resume",
                QueryPhase.DECIDING, 3, state, null, Instant.now(), Instant.now(), Instant.now(), true)));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("恢复完成", List.of()));
        FakeGateway gateway = new FakeGateway(context());
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        service.query(new UnifiedQueryService.QueryRequest("r-resume", "新问题", "p2p", "u1", "c1"));

        assertThat(deepSeek.receivedMessages.get(0).toString()).contains("RESTORED_LEDGER").doesNotContain("新问题");
    }

    @Test
    void repairsMissingToolResponsesFromOlderCheckpointBeforeCallingDeepSeek() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        Map<String, Object> danglingAssistant = Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", "dangling-call",
                        "type", "function",
                        "function", Map.of("name", "mcp_query_tasks", "arguments", "{}"))));
        String state = mapper.writeValueAsString(Map.of("messages", List.of(
                Map.of("role", "system", "content", "RESTORED_LEDGER"), danglingAssistant)));
        when(checkpoints.load("r-repair-ledger")).thenReturn(Optional.of(new QuerySession("r-repair-ledger",
                QueryPhase.DECIDING, 1, state, null, Instant.now(), Instant.now(), Instant.now(), true)));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("恢复完成", List.of()));
        FakeGateway gateway = new FakeGateway(context());
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        service.query(new UnifiedQueryService.QueryRequest(
                "r-repair-ledger", "新问题", "p2p", "u1", "c1"));

        assertThat(deepSeek.receivedMessages.get(0)).anyMatch(message -> "tool".equals(message.get("role"))
                && "dangling-call".equals(message.get("tool_call_id")));
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
    void reusesIdenticalToolCallWithinTaskWithoutRepeatingMcpOrCompaction() {
        DeepSeekConversationClient.ToolCall repeated = tool("same-call", "mcp_query_tasks");
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(repeated)),
                completion(null, List.of(new DeepSeekConversationClient.ToolCall(
                        "same-call-2", repeated.name(), repeated.input()))),
                completion("去重查询完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-cache", "重复查询", "p2p", "u1", "c1"));

        assertThat(gateway.executions).hasValue(1);
        assertThat(result.logicalToolCalls()).isEqualTo(2);
        assertThat(result.physicalMcpCalls()).isEqualTo(1);
        assertThat(result.cacheHits()).isEqualTo(1);
        assertThat(deepSeek.receivedMessages.get(2).toString())
                .contains("cacheHit").contains("reusedCallSignature");
    }

    @Test
    void finalizesAfterTheSingleAllowedReplan() {
        DeepSeekConversationClient.ToolCall repeated = tool("same-1", "mcp_query_tasks");
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(repeated)),
                completion(null, List.of(new DeepSeekConversationClient.ToolCall("same-2", repeated.name(), repeated.input()))),
                completion("基于已有统计回答", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-no-progress", "重复查询", "p2p", "u1", "c1"));

        assertThat(result.stopReason()).isNull();
        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(gateway.executions).hasValue(1);
        assertThat(deepSeek.allowTools).endsWith(false);
    }

    @Test
    void doesNotCacheWaitingAsyncTaskResults() {
        DeepSeekConversationClient.ToolCall repeated = tool("async-1", "mcp_query_tasks");
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(repeated)),
                completion(null, List.of(new DeepSeekConversationClient.ToolCall("async-2", repeated.name(), repeated.input()))),
                completion("异步任务已完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.asyncWaiting = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-async", "查询异步结果", "p2p", "u1", "c1"));

        assertThat(gateway.executions).hasValue(2);
        assertThat(result.cacheHits()).isZero();
    }

    @Test
    void checkpointsWaitingAsyncCallAndYieldsWorkerWithoutAnotherModelRound() {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        when(checkpoints.load("r-yield")).thenReturn(Optional.of(new QuerySession("r-yield",
                QueryPhase.DECIDING, 0, "{}", null, Instant.now(), Instant.now(), Instant.now(), false)));
        when(checkpoints.advance(org.mockito.ArgumentMatchers.eq("r-yield"),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(QueryPhase.POLLING_ASYNC),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion(null,
                List.of(tool("async-call", "mcp_get_async_task_result"))));
        FakeGateway gateway = new FakeGateway(contextWithAsyncTool());
        gateway.asyncWaiting = true;
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        assertThatThrownBy(() -> service.query(new UnifiedQueryService.QueryRequest(
                "r-yield", "查询异步结果", "p2p", "u1", "c1")))
                .isInstanceOf(UnifiedQueryService.QueryPendingException.class);
        assertThat(gateway.executions).hasValue(1);
        assertThat(deepSeek.allowTools).hasSize(1);
    }

    @Test
    void resumesPendingAsyncPollWithoutRepeatingModelDecision() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        Map<String, Object> pending = Map.of(
                "id", "async-call",
                "name", "mcp_get_async_task_result",
                "input", Map.of("projectIds", List.of("P1"), "arguments", Map.of("task_id", "A-1")),
                "pollAttempts", 2,
                "startedAt", "2026-07-13T00:00:00Z");
        String state = mapper.writeValueAsString(Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", "RESTORED"),
                        Map.of("role", "assistant", "content", "", "tool_calls", List.of())),
                "pendingAsync", pending,
                "toolRounds", 1,
                "logicalToolCalls", 1));
        when(checkpoints.load("r-poll-resume")).thenReturn(Optional.of(new QuerySession("r-poll-resume",
                QueryPhase.POLLING_ASYNC, 4, state, Instant.now(), Instant.now(), Instant.now(), Instant.now(), true)));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("异步任务已完成", List.of()));
        FakeGateway gateway = new FakeGateway(contextWithAsyncTool());
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:05Z"), SHANGHAI), checkpoints);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest(
                "r-poll-resume", "查询异步结果", "p2p", "u1", "c1"));

        assertThat(result.answer()).isEqualTo("异步任务已完成");
        assertThat(gateway.executions).hasValue(1);
        assertThat(deepSeek.allowTools).containsExactly(true);
        assertThat(deepSeek.receivedMessages.get(0).stream()
                .filter(message -> "tool".equals(message.get("role"))).toList()).hasSize(1);
    }

    @Test
    void checkpointsPaginationContinuationAfterFivePageBatch() {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        when(checkpoints.load("r-page-yield")).thenReturn(Optional.of(new QuerySession("r-page-yield",
                QueryPhase.DECIDING, 2, "{}", null, Instant.now(), Instant.now(), Instant.now(), false)));
        AtomicReference<String> saved = new AtomicReference<>();
        when(checkpoints.advance(org.mockito.ArgumentMatchers.eq("r-page-yield"),
                org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(QueryPhase.FETCHING_PAGE),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> { saved.set(invocation.getArgument(3)); return true; });
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion(null,
                List.of(tool("page-call", "mcp_query_tasks"))));
        FakeGateway gateway = new FakeGateway(context());
        gateway.paginationWaiting = true;
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        assertThatThrownBy(() -> service.query(new UnifiedQueryService.QueryRequest(
                "r-page-yield", "全量查询", "p2p", "u1", "c1")))
                .isInstanceOf(UnifiedQueryService.QueryPendingException.class);

        assertThat(saved.get()).contains("_paginationStates").contains("nextPage").contains("P1");
        assertThat(deepSeek.allowTools).hasSize(1);
    }

    @Test
    void checkpointsAllConcurrentPaginationContinuationsInsteadOfDroppingAfterFivePages() {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        when(checkpoints.load("r-multi-page-yield")).thenReturn(Optional.of(new QuerySession(
                "r-multi-page-yield", QueryPhase.DECIDING, 3, "{}", null,
                Instant.now(), Instant.now(), Instant.now(), false)));
        AtomicReference<String> saved = new AtomicReference<>();
        when(checkpoints.advance(org.mockito.ArgumentMatchers.eq("r-multi-page-yield"),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(QueryPhase.FETCHING_PAGE),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> { saved.set(invocation.getArgument(3)); return true; });
        DeepSeekConversationClient.ToolCall first = tool("page-call-1", "mcp_query_tasks");
        DeepSeekConversationClient.ToolCall second = new DeepSeekConversationClient.ToolCall(
                "page-call-2", "mcp_query_tasks", Map.of(
                        "projectIds", List.of("P1"), "arguments", Map.of("filter", "second")));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion(null, List.of(first, second)));
        FakeGateway gateway = new FakeGateway(context());
        gateway.paginationWaiting = true;
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        assertThatThrownBy(() -> service.query(new UnifiedQueryService.QueryRequest(
                "r-multi-page-yield", "全量查询两个表单", "p2p", "u1", "c1")))
                .isInstanceOf(UnifiedQueryService.QueryPendingException.class);

        assertThat(saved.get()).contains("pendingExecutions")
                .contains("page-call-1").contains("page-call-2").contains("nextPage");
        assertThat(gateway.executions).hasValue(2);
        assertThat(deepSeek.allowTools).hasSize(1);
    }

    @Test
    void resumesAllConcurrentPaginationContinuationsBeforeReturningToModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        List<Map<String, Object>> pendingExecutions = List.of(
                Map.of("id", "page-call-1", "name", "mcp_query_tasks",
                        "originalToolName", "query_tasks", "completed", false,
                        "input", Map.of("projectIds", List.of("P1"), "arguments", Map.of()),
                        "pollAttempts", 1, "startedAt", "2026-07-13T00:00:00Z"),
                Map.of("id", "page-call-2", "name", "mcp_query_tasks",
                        "originalToolName", "query_tasks", "completed", false,
                        "input", Map.of("projectIds", List.of("P1"),
                                "arguments", Map.of("filter", "second")),
                        "pollAttempts", 1, "startedAt", "2026-07-13T00:00:00Z"));
        String state = mapper.writeValueAsString(Map.of(
                "messages", List.of(Map.of("role", "system", "content", "RESTORED")),
                "pendingExecutions", pendingExecutions,
                "toolRounds", 1,
                "logicalToolCalls", 2,
                "physicalMcpCalls", 10,
                "pages", 10));
        when(checkpoints.load("r-multi-page-resume")).thenReturn(Optional.of(new QuerySession(
                "r-multi-page-resume", QueryPhase.FETCHING_PAGE, 4, state,
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), true)));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("两个表单已全量获取", List.of()));
        FakeGateway gateway = new FakeGateway(context());
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:05Z"), SHANGHAI), checkpoints);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest(
                "r-multi-page-resume", "全量查询两个表单", "p2p", "u1", "c1"));

        assertThat(result.answer()).isEqualTo("两个表单已全量获取");
        assertThat(gateway.executions).hasValue(2);
        assertThat(deepSeek.receivedMessages.get(0).stream()
                .filter(message -> "tool".equals(message.get("role"))).toList()).hasSize(2);
    }

    @Test
    void honorsRetryAfterForTransientMcpFailure() {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        when(checkpoints.load("r-retry-after")).thenReturn(Optional.of(new QuerySession("r-retry-after",
                QueryPhase.DECIDING, 1, "{}", null, Instant.now(), Instant.now(), Instant.now(), false)));
        when(checkpoints.advance(org.mockito.ArgumentMatchers.eq("r-retry-after"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(QueryPhase.POLLING_ASYNC),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion(null, List.of(tool("retry", "mcp_query_tasks"))));
        FakeGateway gateway = new FakeGateway(context());
        gateway.retryAfterSeconds = 20;
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        Throwable thrown = catchThrowable(() -> service.query(new UnifiedQueryService.QueryRequest(
                "r-retry-after", "重试查询", "p2p", "u1", "c1")));

        assertThat(thrown).isInstanceOf(UnifiedQueryService.QueryPendingException.class);
        assertThat(((UnifiedQueryService.QueryPendingException) thrown).resumeAfter()).isEqualTo(java.time.Duration.ofSeconds(20));
    }

    @Test
    void hardDeadlineMakesNoFurtherMcpOrModelCallsAndReturnsCheckpointCoverage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        Map<String, Object> continuation = Map.of("completed", false, "continuation", Map.of(
                "nextPage", 5, "pageFingerprints", List.of(), "successPages", 5, "failedPages", 0,
                "statisticsState", Map.of("fetchedCount", 500, "reportedTotalCount", 1000)));
        Map<String, Object> pending = Map.of(
                "id", "page-call", "name", "mcp_query_tasks", "pollAttempts", 3,
                "startedAt", "2026-07-12T23:30:00Z",
                "input", Map.of("projectIds", List.of("P1"), "arguments", Map.of(
                        "_paginationStates", Map.of("P1", continuation))));
        String state = mapper.writeValueAsString(Map.of("messages", List.of(
                Map.of("role", "system", "content", "RESTORED")), "pendingAsync", pending));
        when(checkpoints.load("r-expired")).thenReturn(Optional.of(new QuerySession("r-expired",
                QueryPhase.FETCHING_PAGE, 7, state, Instant.now(), Instant.now(), Instant.now(), Instant.now(), true)));
        FakeDeepSeek deepSeek = new FakeDeepSeek();
        FakeGateway gateway = new FakeGateway(context());
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:01:00Z"), SHANGHAI);
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(),
                history, mapper, new AnswerPresentationParser(mapper), clock, checkpoints);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest(
                "r-expired", "全量查询", "p2p", "u1", "c1", Instant.parse("2026-07-12T23:30:00Z")));

        assertThat(result.stopReason()).isEqualTo("hard_timeout");
        assertThat(result.answer()).contains("实际获取：500").contains("服务端总数：1000").contains("50.0%");
        assertThat(gateway.executions).hasValue(0);
        assertThat(deepSeek.allowTools).isEmpty();
        assertThat(deepSeek.presentationCalls).isZero();
    }

    @Test
    void neverExceedsTwoPlanningRoundsEvenWhenModelKeepsRequestingTools() {
        List<DeepSeekConversationClient.Completion> completions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            completions.add(completion(null, List.of(new DeepSeekConversationClient.ToolCall(
                    "t" + i, "mcp_query_tasks", Map.of("projectIds", List.of("p1"),
                    "arguments", Map.of("page", i + 1))))));
        }
        completions.add(completion("基于已有数据收敛", List.of()));
        FakeDeepSeek deepSeek = new FakeDeepSeek(completions.toArray(DeepSeekConversationClient.Completion[]::new));
        UnifiedQueryService service = service(deepSeek, new FakeGateway(context()));

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest("r3", "复杂查询", "p2p", "u1", "c1"));

        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(result.stopReason()).isNull();
        assertThat(result.answer()).isEqualTo("基于已有数据收敛");
        assertThat(deepSeek.allowTools).containsExactly(true, true, false);
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
    void deterministicallyBoundsLargeLegacyResultWithoutChunkModelCalls() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t1", "mcp_query_tasks"))),
                completion("大数据分析完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.large = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r5", "分析大量数据", "p2p", "u1", "c1"));

        assertThat(result.chunks()).isZero();
        assertThat(deepSeek.analyzedChunks).isEmpty();
        assertThat(deepSeek.receivedMessages.get(1).toString()).contains("omitted");
    }

    @Test
    void usesDeterministicFullRecordStatisticsWithoutChunkModelCalls() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-stats", "mcp_query_tasks"))),
                completion("全量统计完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-stats", "全量分析", "p2p", "u1", "c1"));

        assertThat(result.chunks()).isZero();
        assertThat(deepSeek.analyzedChunks).isEmpty();
        String toolContent = deepSeek.receivedMessages.get(1).stream()
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content"))).findFirst().orElseThrow();
        assertThat(toolContent).contains("\"recordCount\":250").contains("\"coverageComplete\":true")
                .doesNotContain("payload-text-");
    }

    @Test
    void discoversThenCollectsAndAnalyzesThreeFormsIndependently() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(formTool("discover", "mcp_match_form_resource", "quality"))),
                completion("三张表汇总完成", List.of()));
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-two-stage", "昨天罗诊项目质量情况", "p2p", "u1", "c1"));

        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(result.logicalToolCalls()).isEqualTo(4);
        assertThat(gateway.executions).hasValue(4);
        assertThat(deepSeek.analyzedForms).containsExactlyInAnyOrder("FORM-1", "FORM-2", "FORM-3");
        assertThat(deepSeek.formAnalysisInputs).allSatisfy(input ->
                assertThat(input.length()).isLessThanOrEqualTo(UnifiedQueryService.MAX_FORM_ANALYSIS_CHARS));
        assertThat(deepSeek.analyzedChunks).isNotEmpty();
        assertThat(result.chunks()).isEqualTo(deepSeek.analyzedChunks.size());
        assertThat(deepSeek.allowTools).containsExactly(true, false);
        assertThat(result.answer()).isEqualTo("三张表汇总完成");
    }

    @Test
    void repairsMissingMatchNameAndCollectsWhenModelOmitsEveryStageCall() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of()),
                completion("确定性采集完成", List.of()));
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-deterministic", "替我分析罗诊项目的安全情况", "p2p", "u1", "c1"));

        assertThat(gateway.executedTools).containsExactly(
                "match_form_resource", "query_form_data_list", "query_form_data_list", "query_form_data_list");
        assertThat(gateway.executedArguments.get(0)).containsEntry("name", "安全");
        assertThat(result.physicalMcpCalls()).isEqualTo(4);
        assertThat(result.answer()).isEqualTo("确定性采集完成");
        assertThat(deepSeek.analyzedForms).containsExactlyInAnyOrder("FORM-1", "FORM-2", "FORM-3");
    }

    @Test
    void repairsNameOnModelGeneratedMatchCallUsingRealRequiredSchema() {
        DeepSeekConversationClient.ToolCall missingName = new DeepSeekConversationClient.ToolCall(
                "match-missing-name", "mcp_match_form_resource",
                Map.of("projectIds", List.of("P1"), "arguments", Map.of()));
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(missingName)),
                completion("安全数据完成", List.of()));
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;

        service(deepSeek, gateway).query(new UnifiedQueryService.QueryRequest(
                "r-repair-name", "罗诊项目安全情况", "p2p", "u1", "c1"));

        assertThat(gateway.executedArguments.get(0)).containsEntry("name", "安全");
        assertThat(gateway.executedTools).contains("query_form_data_list");
    }

    @Test
    void collectsAllRelatedCandidatesAcrossJavaManagedBatchesWithoutCountCap() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of()), completion("十二张表采集完成", List.of()));
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;
        gateway.discoveryFormCount = 12;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-ten-forms", "项目安全情况", "p2p", "u1", "c1"));

        assertThat(gateway.executedTools.stream().filter("query_form_data_list"::equals).count()).isEqualTo(12);
        assertThat(deepSeek.analyzedForms).hasSize(12);
        assertThat(result.logicalToolCalls()).isEqualTo(13);
        assertThat(deepSeek.allowTools).containsExactly(true, false);
    }

    @Test
    void continuesWithPartialAnalysisWhenOneRelatedFormCollectionFails() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of()), completion("其余十一张表已完成部分分析", List.of()));
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;
        gateway.discoveryFormCount = 12;
        gateway.failedCollectionFormId = "FORM-5";

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-partial-forms", "项目安全情况", "p2p", "u1", "c1"));

        assertThat(gateway.executedTools.stream().filter("query_form_data_list"::equals).count()).isEqualTo(12);
        assertThat(deepSeek.analyzedForms).hasSize(11).doesNotContain("FORM-5");
        assertThat(result.failures()).anyMatch(failure -> failure.contains("FORM-5")
                && failure.contains("没有取得可分析的成功数据"));
        assertThat(result.answer()).contains("部分分析").contains("数据缺口");
    }

    @Test
    void resumesAllCandidateFormsAfterPaginatedCollectionCheckpoint() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository firstCheckpoints = mock(QueryCheckpointRepository.class);
        when(firstCheckpoints.load("r-all-forms-resume")).thenReturn(Optional.of(new QuerySession(
                "r-all-forms-resume", QueryPhase.DECIDING, 1, "{}", null,
                Instant.now(), Instant.now(), Instant.now(), false)));
        AtomicReference<String> saved = new AtomicReference<>();
        when(firstCheckpoints.advance(org.mockito.ArgumentMatchers.eq("r-all-forms-resume"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(QueryPhase.FETCHING_PAGE),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> { saved.set(invocation.getArgument(3)); return true; });
        FakeGateway firstGateway = new FakeGateway(formContext());
        firstGateway.structuredRecords = true;
        firstGateway.discoveryFormCount = 12;
        firstGateway.formPaginationWaiting = true;
        FakeDeepSeek firstDeepSeek = new FakeDeepSeek(completion(null, List.of()));
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService firstService = new UnifiedQueryService(firstDeepSeek, firstGateway,
                new McpToolDefinitionMapper(), history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), firstCheckpoints);

        assertThatThrownBy(() -> firstService.query(new UnifiedQueryService.QueryRequest(
                "r-all-forms-resume", "项目安全情况", "p2p", "u1", "c1")))
                .isInstanceOf(UnifiedQueryService.QueryPendingException.class);
        assertThat(saved.get()).contains("candidateForms").contains("FORM-12")
                .contains("selectedFormIds").contains("COLLECTION_PLANNING");

        QueryCheckpointRepository resumedCheckpoints = mock(QueryCheckpointRepository.class);
        when(resumedCheckpoints.load("r-all-forms-resume")).thenReturn(Optional.of(new QuerySession(
                "r-all-forms-resume", QueryPhase.FETCHING_PAGE, 2, saved.get(), null,
                Instant.now(), Instant.now(), Instant.now(), true)));
        FakeGateway resumedGateway = new FakeGateway(formContext());
        resumedGateway.structuredRecords = true;
        resumedGateway.discoveryFormCount = 12;
        FakeDeepSeek resumedDeepSeek = new FakeDeepSeek(completion("十二张表恢复后分析完成", List.of()));
        UnifiedQueryService resumedService = new UnifiedQueryService(resumedDeepSeek, resumedGateway,
                new McpToolDefinitionMapper(), history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:05Z"), SHANGHAI), resumedCheckpoints);

        UnifiedQueryService.QueryResult result = resumedService.query(new UnifiedQueryService.QueryRequest(
                "r-all-forms-resume", "项目安全情况", "p2p", "u1", "c1"));

        assertThat(resumedDeepSeek.analyzedForms).hasSize(12);
        assertThat(result.answer()).contains("十二张表恢复后分析完成");
    }

    @Test
    void fallsBackToListOnlyAfterMatchReturnsNoCandidates() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of()), completion(null, List.of()),
                completion("列表兜底采集完成", List.of()));
        FakeGateway gateway = new FakeGateway(formContextWithList());
        gateway.structuredRecords = true;
        gateway.emptyMatch = true;

        service(deepSeek, gateway).query(new UnifiedQueryService.QueryRequest(
                "r-list-fallback", "项目安全情况", "p2p", "u1", "c1"));

        assertThat(gateway.executedTools).startsWith("match_form_resource", "list_form_resource");
        assertThat(gateway.executedTools.stream().filter("query_form_data_list"::equals).count()).isEqualTo(3);
    }

    @Test
    void fallsBackToDeterministicStatisticsWhenOneFormAnalysisFails() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(formTool("discover", "mcp_match_form_resource", "quality"))),
                completion("基于降级统计完成", List.of()));
        deepSeek.failedFormId = "FORM-1";
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-form-fallback", "质量情况", "p2p", "u1", "c1"));

        assertThat(deepSeek.formAnalysisAttempts).hasValue(4);
        assertThat(deepSeek.analyzedForms.stream().filter("FORM-1"::equals).count()).isEqualTo(2);
        assertThat(result.failures()).anyMatch(failure -> failure.contains("DeepSeek 分析失败"));
        assertThat(deepSeek.receivedMessages.get(1).toString()).contains("\"fallback\":true");
        assertThat(result.answer()).contains("基于降级统计完成").contains("数据缺口");
    }

    @Test
    void neverStartsGenericSemanticChunksForOversizedPayloads() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-budget", "mcp_query_tasks"))),
                completion("基于预算内数据完成", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.oversizedLegacyPayload = true;

        service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-budget", "超大结果", "p2p", "u1", "c1"));

        assertThat(deepSeek.analyzedChunks).isEmpty();
        assertThat(deepSeek.receivedMessages.get(1).toString().length()).isLessThan(30_000);
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
        assertThat(result.failures()).anyMatch(failure -> failure.contains("未执行其余 1"));
    }

    @Test
    void disclosesWhenAutomaticPaginationReachesHardDeadline() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-limit", "mcp_query_tasks"))),
                completion("基于已获取分页的分析", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.truncated = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r7", "分页上限", "p2p", "u1", "c1"));

        assertThat(result.answer()).contains("查询硬截止时间").contains("数据缺口");
        assertThat(result.failures()).anyMatch(failure -> failure.contains("查询硬截止时间"));
    }

    @Test
    void legacyLargeResultIsBoundedWithoutChunkFailurePath() {
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
        assertThat(toolContent).contains("omitted");
    }

    @Test
    void preservesProjectAttributionInDeterministicCrossProjectSummary() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-projects", "mcp_query_tasks"))),
                completion("跨项目结论", List.of())
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.large = true;
        gateway.multiProjectLarge = true;

        service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r9", "跨项目大数据", "p2p", "u1", "c1"));

        assertThat(deepSeek.receivedMessages.get(1).toString()).contains("P1").contains("P2");
    }

    @Test
    void returnsDeterministicPartialResultWhenALaterDeepSeekRoundFails() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("t-before-failure", "mcp_query_tasks")))
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r10", "后续轮失败", "p2p", "u1", "c1"));

        assertThat(result.stopReason()).isEqualTo("deepseek_response_error");
        assertThat(result.path()).isEqualTo("mcp_deepseek");
        assertThat(result.logicalToolCalls()).isEqualTo(1);
        assertThat(result.physicalMcpCalls()).isEqualTo(1);
        assertThat(result.answer()).contains("查询部分结果").contains("以上结论仅基于已成功取得的数据");
        assertThat(result.failures()).anyMatch(failure -> failure.contains("DeepSeek"));
        assertThat(deepSeek.presentationCalls).isZero();
    }

    @Test
    void returnsPartialResultWhenFinalizationResponseFailsAfterNoProgress() {
        DeepSeekConversationClient.ToolCall repeated = tool("same-1", "mcp_query_tasks");
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(repeated)),
                completion(null, List.of(new DeepSeekConversationClient.ToolCall("same-2", repeated.name(), repeated.input())))
        );
        FakeGateway gateway = new FakeGateway(context());
        gateway.structuredRecords = true;

        UnifiedQueryService.QueryResult result = service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("r-final-failure", "重复查询", "p2p", "u1", "c1"));

        assertThat(result.stopReason()).isEqualTo("deepseek_response_error");
        assertThat(result.answer()).contains("查询部分结果");
        assertThat(gateway.executions).hasValue(1);
        assertThat(deepSeek.presentationCalls).isZero();
    }

    @Test
    void preservesSuccessfulObservationCountAcrossCheckpointRecovery() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        QueryCheckpointRepository checkpoints = mock(QueryCheckpointRepository.class);
        String state = mapper.writeValueAsString(Map.of(
                "messages", List.of(Map.of("role", "system", "content", "RESTORED")),
                "toolRounds", 2,
                "logicalToolCalls", 1,
                "physicalMcpCalls", 1,
                "pages", 1,
                "successfulObservations", 1));
        when(checkpoints.load("r-recovered-model-failure")).thenReturn(Optional.of(new QuerySession(
                "r-recovered-model-failure", QueryPhase.DECIDING, 3, state, null,
                Instant.now(), Instant.now(), Instant.now(), true)));
        FakeDeepSeek deepSeek = new FakeDeepSeek();
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        UnifiedQueryService service = new UnifiedQueryService(deepSeek, new FakeGateway(context()),
                new McpToolDefinitionMapper(), history, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI), checkpoints);

        UnifiedQueryService.QueryResult result = service.query(new UnifiedQueryService.QueryRequest(
                "r-recovered-model-failure", "继续查询", "p2p", "u1", "c1"));

        assertThat(result.stopReason()).isEqualTo("deepseek_response_error");
        assertThat(result.physicalMcpCalls()).isEqualTo(1);
        assertThat(result.answer()).contains("查询部分结果");
    }

    @Test
    void resolvesYesterdayAgainstShanghaiCalendarAndForbidsDateFallback() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("无数据", List.of()));
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T13:18:43Z"), SHANGHAI);

        service(deepSeek, new FakeGateway(context()), clock).query(
                new UnifiedQueryService.QueryRequest("r-date", "昨天罗诊的质量情况", "p2p", "u1", "c1"));

        String systemPrompt = String.valueOf(deepSeek.receivedMessages.get(0).get(0).get("content"));
        assertThat(systemPrompt)
                .contains("当前日期：2026-07-13")
                .contains("“昨天”的目标日期：2026-07-12")
                .contains("不得用其他日期代替")
                .contains("2026-07-12 无质量记录");
    }

    @Test
    void usesShanghaiDateWhenUtcCalendarIsStillPreviousDay() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("无数据", List.of()));
        Clock clock = Clock.fixed(Instant.parse("2026-07-12T16:30:00Z"), SHANGHAI);

        service(deepSeek, new FakeGateway(context()), clock).query(
                new UnifiedQueryService.QueryRequest("r-midnight", "昨天的质量问题", "p2p", "u1", "c1"));

        String systemPrompt = String.valueOf(deepSeek.receivedMessages.get(0).get(0).get("content"));
        assertThat(systemPrompt).contains("当前日期：2026-07-13")
                .contains("目标日期：2026-07-12");
    }

    @Test
    void requestsOrderedPresentationBlocksInsteadOfDetachedCollections() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("分析完成", List.of()));

        service(deepSeek, new FakeGateway(context())).query(
                new UnifiedQueryService.QueryRequest("r-blocks", "分析质量趋势", "p2p", "u1", "c1"));

        String instruction = String.valueOf(deepSeek.presentationMessages.get(0).get(0).get("content"));
        assertThat(instruction)
                .contains("plainText、blocks 两个字段")
                .contains("数组顺序就是飞书卡片的展示顺序")
                .contains("不得输出“详见最后图表”")
                .contains("表格块最多 5 个")
                .contains("图表块最多 3 个")
                .contains("合并同类表格")
                .contains("改为 Markdown")
                .contains("不得通过拆分同一份数据规避组件上限");
    }

    @Test
    void overridesWrongModelDateArgumentsWithResolvedYesterdayForSupportedSchemaFields() {
        DeepSeekConversationClient.ToolCall wrongDateCall = new DeepSeekConversationClient.ToolCall(
                "t-date", "mcp_query_tasks", Map.of(
                "projectIds", List.of("P1"),
                "arguments", Map.of("date", "2026-07-08", "start_date", "2026-07-08", "end_date", "2026-07-08")));
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(wrongDateCall)), completion("2026-07-12 无质量记录", List.of()));
        FakeGateway gateway = new FakeGateway(contextWithDateFields());
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T13:18:43Z"), SHANGHAI);

        service(deepSeek, gateway, clock).query(
                new UnifiedQueryService.QueryRequest("r-date-args", "昨天罗诊的质量情况", "p2p", "u1", "c1"));

        assertThat(gateway.lastArguments).containsEntry("date", "2026-07-12")
                .containsEntry("start_date", "2026-07-12")
                .containsEntry("end_date", "2026-07-12");
    }

    @Test
    void injectsLastMonthIntoRealNestedFormFilterForDeterministicCollection() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of()), completion("上个月作业票完成", List.of()));
        FakeGateway gateway = new FakeGateway(formContext());
        gateway.structuredRecords = true;
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T06:00:00Z"), SHANGHAI);

        service(deepSeek, gateway, clock).query(new UnifiedQueryService.QueryRequest(
                "r-last-month", "上个月罗诊项目申请了哪些作业票", "p2p", "u1", "c1"));

        Map<String, Object> formArguments = gateway.executedArguments.stream()
                .filter(arguments -> arguments.containsKey("formId")).findFirst().orElseThrow();
        assertThat((Map<String, Object>) formArguments.get("filter"))
                .containsEntry("createTime", List.of("2026-06-01 00:00:00", "2026-06-30 23:59:59"));
    }

    @Test
    void usesDomainNeutralNoDataWordingOutsideQualityQuestions() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(completion("无数据", List.of()));

        service(deepSeek, new FakeGateway(context())).query(
                new UnifiedQueryService.QueryRequest("r-progress-date", "昨天的进度情况", "p2p", "u1", "c1"));

        String systemPrompt = String.valueOf(deepSeek.receivedMessages.get(0).get(0).get("content"));
        assertThat(systemPrompt).contains("2026-07-12 当日无相关记录")
                .doesNotContain("2026-07-12 无质量记录");
    }

    @Test
    void threadsRealTraceDependenciesAcrossDiscoveryDecisionToolFollowupAndPresentation() {
        FakeDeepSeek deepSeek = new FakeDeepSeek(
                completion(null, List.of(tool("logical-1", "mcp_query_tasks"))),
                completion("最终结论", List.of()));
        McpQueryGateway.QueryContext tracedContext = new McpQueryGateway.QueryContext(
                "pl1", List.of(new McpQueryGateway.Project("P1", "项目一")),
                context().availableTools(), null, List.of("discovery-1"));
        FakeGateway gateway = new FakeGateway(tracedContext);
        gateway.traceEventIds = List.of("tool-event-1");

        service(deepSeek, gateway).query(
                new UnifiedQueryService.QueryRequest("trace-request", "分析项目", "p2p", "u1", "c1"));

        assertThat(gateway.loadedRequestId).isEqualTo("trace-request");
        assertThat(deepSeek.traceContexts).hasSize(2);
        assertThat(deepSeek.traceContexts.get(0).dependencyEventIds()).containsExactly("discovery-1");
        assertThat(gateway.executionTrace.parentEventId()).isEqualTo("model-1");
        assertThat(gateway.executionTrace.logicalCallId()).isEqualTo("logical-1");
        assertThat(deepSeek.traceContexts.get(1).dependencyEventIds()).containsExactly("tool-event-1");
        assertThat(deepSeek.traceContexts.get(0).purpose()).isEqualTo("planning");
        assertThat(deepSeek.traceContexts.get(1).purpose()).isEqualTo("replanning");
        assertThat(deepSeek.presentationCalls).isZero();
    }

    private UnifiedQueryService service(FakeDeepSeek deepSeek, FakeGateway gateway) {
        return service(deepSeek, gateway, Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), SHANGHAI));
    }

    private UnifiedQueryService service(FakeDeepSeek deepSeek, FakeGateway gateway, Clock clock) {
        ConversationHistoryProvider history = (chatType, openId, chatId, requestId) -> List.of();
        ObjectMapper objectMapper = new ObjectMapper();
        return new UnifiedQueryService(deepSeek, gateway, new McpToolDefinitionMapper(), history, objectMapper,
                new AnswerPresentationParser(objectMapper), clock);
    }

    private static McpQueryGateway.QueryContext context() {
        return new McpQueryGateway.QueryContext("pl1",
                List.of(new McpQueryGateway.Project("P1", "项目一"), new McpQueryGateway.Project("P2", "项目二")),
                List.of(
                        Map.of("name", "query_tasks", "description", "tasks", "inputSchema", Map.of("type", "object")),
                        Map.of("name", "get_report", "description", "report", "inputSchema", Map.of("type", "object"))
                ), null);
    }

    private static McpQueryGateway.QueryContext contextWithDateFields() {
        Map<String, Object> dateProperty = Map.of("type", "string", "format", "date");
        return new McpQueryGateway.QueryContext("pl1",
                List.of(new McpQueryGateway.Project("P1", "项目一")),
                List.of(Map.of(
                        "name", "query_tasks",
                        "description", "tasks",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "date", dateProperty,
                                        "start_date", dateProperty,
                                        "end_date", dateProperty)))),
                null);
    }

    private static McpQueryGateway.QueryContext formContext() {
        return new McpQueryGateway.QueryContext("pl1",
                List.of(new McpQueryGateway.Project("P1", "项目一")),
                List.of(
                        Map.of("name", "match_form_resource", "description", "match form",
                                "inputSchema", Map.of("type", "object",
                                        "required", List.of("name"),
                                        "properties", Map.of("name", Map.of("type", "string")))),
                        Map.of("name", "query_form_data_list", "description", "query form data",
                                "inputSchema", Map.of("type", "object",
                                        "required", List.of("formId"),
                                        "properties", Map.of("formId", Map.of("type", "string"),
                                                "filter", Map.of("type", "object", "properties", Map.of(
                                                        "createTime", Map.of("type", "array"),
                                                        "processFinishTime", Map.of("type", "array"),
                                                        "approvalArrivalTime", Map.of("type", "array"))),
                                                "page", Map.of("type", "integer"),
                                                "pageSize", Map.of("type", "integer"))))), null);
    }

    private static McpQueryGateway.QueryContext formContextWithList() {
        List<Map<String, Object>> tools = new ArrayList<>(formContext().availableTools());
        tools.add(1, Map.of("name", "list_form_resource", "description", "list form",
                "inputSchema", Map.of("type", "object")));
        return new McpQueryGateway.QueryContext("pl1", formContext().projects(), tools, null);
    }

    private static McpQueryGateway.QueryContext contextWithAsyncTool() {
        return new McpQueryGateway.QueryContext("pl1", List.of(new McpQueryGateway.Project("P1", "项目一")),
                List.of(Map.of("name", "get_async_task_result", "description", "async",
                        "inputSchema", Map.of("type", "object"))), null);
    }

    private static DeepSeekConversationClient.ToolCall tool(String id, String name) {
        return new DeepSeekConversationClient.ToolCall(id, name, Map.of("projectIds", List.of("P1"), "arguments", Map.of()));
    }

    private static DeepSeekConversationClient.ToolCall formTool(String id, String name, String formId) {
        return new DeepSeekConversationClient.ToolCall(id, name, Map.of(
                "projectIds", List.of("P1"), "arguments", Map.of("formId", formId)));
    }

    private static DeepSeekConversationClient.Completion completion(String content, List<DeepSeekConversationClient.ToolCall> calls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        if (!calls.isEmpty()) {
            message.put("tool_calls", calls.stream().map(call -> Map.<String, Object>of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", "{}"))).toList());
        }
        return new DeepSeekConversationClient.Completion(content, calls, message, 10, 5);
    }

    private static class FakeDeepSeek implements DeepSeekConversationClient {
        private final Deque<Completion> completions = new ArrayDeque<>();
        private final List<Boolean> allowTools = new ArrayList<>();
        private final List<String> analyzedChunks = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> analyzedProjects = new ArrayList<>();
        private final List<String> analyzedForms = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> formAnalysisInputs = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger formAnalysisAttempts = new AtomicInteger();
        private String failedFormId;
        private final List<List<Map<String, Object>>> receivedMessages = new ArrayList<>();
        private final List<List<Map<String, Object>>> presentationMessages = new ArrayList<>();
        private final List<String> selectedModels = new ArrayList<>();
        private int failedChunk = -1;
        private int modelReads;
        private int presentationCalls;
        private int tracedCalls;
        private final List<TraceContext> traceContexts = new ArrayList<>();

        FakeDeepSeek(Completion... values) { completions.addAll(List.of(values)); }

        @Override
        public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean allowTools) {
            assertToolCallTranscriptClosed(messages);
            this.allowTools.add(allowTools);
            this.receivedMessages.add(List.copyOf(messages));
            return completions.removeFirst();
        }

        private void assertToolCallTranscriptClosed(List<Map<String, Object>> messages) {
            for (int index = 0; index < messages.size(); index++) {
                Object rawCalls = messages.get(index).get("tool_calls");
                if (!(rawCalls instanceof List<?> calls) || calls.isEmpty()) continue;
                Set<String> expected = new LinkedHashSet<>();
                for (Object rawCall : calls) {
                    if (rawCall instanceof Map<?, ?> call && call.get("id") != null) {
                        expected.add(String.valueOf(call.get("id")));
                    }
                }
                Set<String> actual = new LinkedHashSet<>();
                int responseIndex = index + 1;
                while (responseIndex < messages.size()
                        && "tool".equals(String.valueOf(messages.get(responseIndex).get("role")))) {
                    Object id = messages.get(responseIndex).get("tool_call_id");
                    if (id != null) actual.add(String.valueOf(id));
                    responseIndex++;
                }
                assertThat(actual).containsAll(expected);
            }
        }

        @Override
        public Completion complete(String selectedModel, List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools, boolean allowTools) {
            selectedModels.add(selectedModel);
            return complete(messages, tools, allowTools);
        }

        @Override
        public Completion complete(TraceContext traceContext, String selectedModel,
                                   List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   boolean allowTools) {
            traceContexts.add(traceContext);
            Completion value = complete(selectedModel, messages, tools, allowTools);
            tracedCalls++;
            return new Completion(value.content(), value.toolCalls(), value.assistantMessage(),
                    value.inputTokens(), value.outputTokens(), "model-" + tracedCalls, 1, Map.of());
        }

        @Override
        public Completion formatPresentation(String selectedModel, List<Map<String, Object>> messages) {
            selectedModels.add(selectedModel);
            presentationCalls++;
            presentationMessages.add(List.copyOf(messages));
            String draft = String.valueOf(messages.get(messages.size() - 1).get("content"));
            String escaped = draft.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String json = "{\"plainText\":\"" + escaped + "\",\"blocks\":[{\"type\":\"markdown\",\"content\":\""
                    + escaped + "\"}]}";
            return new Completion(json, List.of(), Map.of("role", "assistant", "content", json), 4, 2);
        }

        @Override
        public Completion formatPresentation(TraceContext traceContext, String selectedModel,
                                             List<Map<String, Object>> messages) {
            traceContexts.add(traceContext);
            Completion value = formatPresentation(selectedModel, messages);
            tracedCalls++;
            return new Completion(value.content(), value.toolCalls(), value.assistantMessage(),
                    value.inputTokens(), value.outputTokens(), "model-" + tracedCalls, 1, Map.of());
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
        @Override
        public ChunkAnalysis analyzeFormWithUsage(TraceContext traceContext, String selectedModel,
                                                  String formId, String formName, String compactJson) {
            traceContexts.add(traceContext);
            analyzedForms.add(formId);
            formAnalysisInputs.add(compactJson);
            int attempt = formAnalysisAttempts.incrementAndGet();
            if (formId.equals(failedFormId)) throw new IllegalStateException("form analysis unavailable");
            return new ChunkAnalysis("{\"summary\":\"" + formId + "\"}", 3, 2,
                    "form-model-" + attempt);
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
        boolean structuredRecords;
        boolean oversizedLegacyPayload;
        boolean asyncWaiting;
        boolean paginationWaiting;
        boolean formPaginationWaiting;
        int retryAfterSeconds;
        int discoveryFormCount = 3;
        String failedCollectionFormId;
        boolean emptyMatch;
        Map<String, Object> lastArguments = Map.of();
        String loadedRequestId;
        ExecutionTrace executionTrace;
        List<String> traceEventIds = List.of();
        final List<String> executedTools = new ArrayList<>();
        final List<Map<String, Object>> executedArguments = new ArrayList<>();

        FakeGateway(QueryContext context) { this.context = context; }
        @Override public QueryContext loadContext(String openId, String chatId, String chatType) { return context; }
        @Override public QueryContext loadContext(String requestId, String openId, String chatId, String chatType) {
            loadedRequestId = requestId;
            return context;
        }

        @Override
        public List<ToolObservation> execute(String requestId, QueryContext context, String toolName, List<String> projectIds, Map<String, Object> arguments) {
            executions.incrementAndGet();
            lastArguments = Map.copyOf(arguments);
            synchronized (executedTools) {
                executedTools.add(toolName);
                executedArguments.add(Map.copyOf(arguments));
            }
            List<ToolObservation> result = new ArrayList<>();
            if ("query_form_data_list".equals(toolName)
                    && String.valueOf(arguments.get("formId")).equals(failedCollectionFormId)) {
                result.add(new ToolObservation("P1", "项目一", toolName, "FAILED", Map.of(),
                        "表单 " + failedCollectionFormId + " 获取失败", 1, 0, false));
                return result;
            }
            Map<String, Object> payload = retryAfterSeconds > 0
                    ? Map.of("asyncTaskState", "RETRYING", "asyncTaskTerminal", false,
                    "retryable", true, "retryAfterSeconds", retryAfterSeconds)
                    : (paginationWaiting || formPaginationWaiting && "query_form_data_list".equals(toolName))
                    ? Map.of("fetchedCount", 500, "reportedTotalCount", 1000,
                    "asyncTaskState", "PAGINATING", "asyncTaskTerminal", false,
                    "paginationState", Map.of("completed", false,
                            "continuation", Map.of("nextPage", 5, "pageFingerprints", List.of("h1"),
                                    "successPages", 5, "failedPages", 0,
                                    "statisticsState", Map.of("fetchedCount", 500))))
                    : asyncWaiting
                    ? Map.of("result", Map.of("status", "PENDING"), "asyncTaskState", "PENDING", "asyncTaskTerminal", false)
                    : structuredRecords && emptyMatch && "match_form_resource".equals(toolName)
                    ? Map.of("forms", List.of())
                    : structuredRecords && ("match_form_resource".equals(toolName)
                            || "list_form_resource".equals(toolName))
                    ? discoveryPayload()
                    : structuredRecords
                    ? structuredPayload(arguments)
                    : oversizedLegacyPayload
                    ? Map.of("pages", java.util.stream.IntStream.range(0, 10)
                            .mapToObj(index -> Map.of("data", ("payload-text-" + index + "-").repeat(2_000)))
                            .toList())
                    : large
                    ? Map.of("pages", List.of(Map.of("data", "x".repeat(20_000)), Map.of("data", "y".repeat(20_000) + "TAIL_MARKER")))
                    : Map.of("data", List.of(1, 2), "arguments", arguments);
            result.add(new ToolObservation("P1", "项目一", toolName, "SUCCEEDED", payload, null,
                    1, large ? 2 : 1, truncated, 2, null, traceEventIds));
            if (multiProjectLarge) result.add(new ToolObservation("P2", "项目二", toolName, "SUCCEEDED",
                    Map.of("pages", List.of(Map.of("data", "z".repeat(20_000)))), null, 1, 1, false));
            if (partial) result.add(new ToolObservation("P2", "项目二", toolName, "FAILED", Map.of(), "P2 查询失败", 1, 0, false));
            return result;
        }

        private Map<String, Object> discoveryPayload() {
            List<Map<String, Object>> forms = java.util.stream.IntStream.rangeClosed(1, discoveryFormCount)
                    .mapToObj(index -> Map.<String, Object>of(
                            "formId", "FORM-" + index,
                            "name", "安全表单" + index))
                    .toList();
            return Map.of("forms", forms);
        }

        private Map<String, Object> structuredPayload(Map<String, Object> arguments) {
            String createTime = "2026-06-15 12:00:00";
            if (arguments.get("filter") instanceof Map<?, ?> filter
                    && filter.get("createTime") instanceof List<?> values && !values.isEmpty()) {
                createTime = String.valueOf(values.get(0));
            }
            String recordTime = createTime;
            List<Map<String, Object>> records = java.util.stream.IntStream.range(0, 250)
                    .mapToObj(index -> Map.<String, Object>of(
                            "id", "R-" + index,
                            "status", index < 200 ? "Closed" : "Open",
                            "createTime", recordTime,
                            "description", "payload-text-" + "x".repeat(1_000)))
                    .toList();
            return Map.of("records", records, "fetchedCount", 250, "reportedTotalCount", 250,
                    "coverageComplete", true, "pageCount", 3, "pageLimitReached", false);
        }

        @Override
        public List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                             List<String> projectIds, Map<String, Object> arguments,
                                             ExecutionTrace trace) {
            executionTrace = trace;
            return execute(requestId, context, toolName, projectIds, arguments);
        }
    }
}
