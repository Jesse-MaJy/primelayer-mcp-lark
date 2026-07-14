package com.larkconnect.agent.agent;

import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest {
    @Test
    void sendsBusinessQuestionThroughUnifiedQueryService() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        UnifiedQueryService unified = mock(UnifiedQueryService.class);
        FeishuClient feishu = mock(FeishuClient.class);
        AuditService audit = mock(AuditService.class);
        ChainTraceService traces = mock(ChainTraceService.class);
        when(tasks.loadTask("r1")).thenReturn(task("分析 Roche 本月质量风险"));
        when(unified.query(any())).thenReturn(result("mcp_deepseek", "风险分析完成"));

        orchestrator(tasks, unified, feishu, audit, traces).process("r1");

        verify(unified).query(new UnifiedQueryService.QueryRequest("r1", "分析 Roche 本月质量风险", "p2p", "u1", "c1"));
        verify(feishu).replyAnswerFeedbackCard(
                eq("m1"), eq("r1"), eq("分析 Roche 本月质量风险"),
                any(AnswerPresentation.class),
                eq("项目数据分析"), eq("blue"));
        verify(traces).save(eq("r1"), any());
        verify(audit).updateProcessingLatency(eq("r1"), any(Long.class));
        verify(audit).writeModel(eq("r1"), eq("deepseek-v4-pro"), eq("mcp_deepseek"),
                argThat(summary -> summary.contains("physicalMcpCalls=2")
                        && summary.contains("pages=2")
                        && summary.contains("inputTokens=20")),
                eq("{\"plainText\":\"风险分析完成\"}"), eq("SUCCEEDED"), eq(100L), eq(null));
        var order = inOrder(audit, feishu);
        order.verify(audit).writeMain(eq("r1"), eq("u1"), eq("c1"), eq(null), eq(List.of("P1")),
                eq("分析 Roche 本月质量风险"), eq("mcp_deepseek"), eq("风险分析完成"),
                eq("{\"plainText\":\"风险分析完成\"}"), any(Long.class), eq(null));
        order.verify(feishu).replyAnswerFeedbackCard(
                eq("m1"), eq("r1"), eq("分析 Roche 本月质量风险"), any(AnswerPresentation.class),
                eq("项目数据分析"), eq("blue"));
    }

    @Test
    void keepsMcpConfigurationCheckOutsideBusinessAnswerEngine() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        UnifiedQueryService unified = mock(UnifiedQueryService.class);
        FeishuClient feishu = mock(FeishuClient.class);
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        AuditService audit = mock(AuditService.class);
        ChainTraceService traces = mock(ChainTraceService.class);
        when(tasks.loadTask("r2")).thenReturn(task("检查 MCP 配置"));
        when(resolver.checkMcpConfig("u1", "c1", "p2p", 20)).thenReturn(
                TokenResolver.McpConfigCheckResult.configured("u1", "pl", "OPEN_ID", "u1", List.of()));
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(
                TokenResolver.ResolvedContext.ok("pl", List.of()));

        new AgentOrchestrator(tasks, unified, resolver, adapter, feishu, audit, traces, new ControlCommandRouter(), 20).process("r2");

        verify(unified, never()).query(any());
        verify(feishu).replyAnswerCard(eq("m1"), eq("检查 MCP 配置"), any(), eq("MCP 配置状态"), any());
    }

    @Test
    void reportsFailedOutcomeWhenDeepSeekIsUnavailable() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        UnifiedQueryService unified = mock(UnifiedQueryService.class);
        FeishuClient feishu = mock(FeishuClient.class);
        AuditService audit = mock(AuditService.class);
        ChainTraceService traces = mock(ChainTraceService.class);
        when(tasks.loadTask("r3")).thenReturn(task("分析项目"));
        when(unified.query(any())).thenThrow(new IllegalStateException("DeepSeek unavailable"));

        AgentOrchestrator.ProcessResult outcome = orchestrator(tasks, unified, feishu, audit, traces).process("r3");

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.error()).isEqualTo("AI 服务暂不可用");
        verify(feishu).replyAnswerCard("m1", "分析项目", "AI 服务暂不可用，已记录本次请求，请稍后重试。", "AI 服务异常", "orange");
        verify(traces).save(eq("r3"), any());
    }

    @Test
    void marksRecoveredDeepSeekResponseFailureAsPartialInsteadOfFailed() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        UnifiedQueryService unified = mock(UnifiedQueryService.class);
        FeishuClient feishu = mock(FeishuClient.class);
        AuditService audit = mock(AuditService.class);
        ChainTraceService traces = mock(ChainTraceService.class);
        when(tasks.loadTask("r-partial")).thenReturn(task("分析项目"));
        UnifiedQueryService.QueryResult partial = new UnifiedQueryService.QueryResult(
                "mcp_deepseek", "已取得数据的部分结果", AnswerPresentation.markdownOnly("部分结果"),
                null, "deepseek-v4-pro", 1, 1, 1, 1, 0, 0,
                List.of("query_tasks"), List.of("P1"), List.of("DeepSeek 响应解析失败"),
                "deepseek_response_error", 20, 10, 0, 100);
        when(unified.query(any())).thenReturn(partial);

        AgentOrchestrator.ProcessResult outcome = orchestrator(tasks, unified, feishu, audit, traces)
                .process("r-partial");

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.partial()).isTrue();
        verify(feishu).replyAnswerFeedbackCard(eq("m1"), eq("r-partial"), eq("分析项目"),
                any(AnswerPresentation.class), eq("项目数据分析（部分结果）"), eq("orange"));
    }

    @Test
    void reportsFeishuDeliveryFailureWithoutOverwritingSuccessfulAiResult() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        UnifiedQueryService unified = mock(UnifiedQueryService.class);
        FeishuClient feishu = mock(FeishuClient.class);
        AuditService audit = mock(AuditService.class);
        ChainTraceService traces = mock(ChainTraceService.class);
        when(tasks.loadTask("r-send")).thenReturn(task("分析项目"));
        when(unified.query(any())).thenReturn(result("mcp_deepseek", "风险分析完成"));
        when(feishu.replyAnswerFeedbackCard(
                eq("m1"), eq("r-send"), eq("分析项目"), any(AnswerPresentation.class),
                eq("项目数据分析"), eq("blue")))
                .thenThrow(new IllegalStateException("card table number over limit"));

        AgentOrchestrator.ProcessResult outcome = orchestrator(tasks, unified, feishu, audit, traces).process("r-send");

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.error()).isEqualTo(
                "飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）");
        verify(audit, never()).writeModel(any(), any(), eq("unified_query"), any(), any(),
                eq("FAILED"), any(Long.class), any());
        verify(audit).writeMain(eq("r-send"), eq("u1"), eq("c1"), eq(null), eq(List.of("P1")),
                eq("分析项目"), eq("mcp_deepseek"), eq("风险分析完成"),
                eq("{\"plainText\":\"风险分析完成\"}"), any(Long.class),
                eq("飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）"));
        verify(feishu).replyAnswerCard("m1", "分析项目",
                "AI 已完成回答，但飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）",
                "飞书消息发送失败", "orange");
    }

    private AgentOrchestrator orchestrator(AgentTaskService tasks, UnifiedQueryService unified,
                                           FeishuClient feishu, AuditService audit, ChainTraceService traces) {
        return new AgentOrchestrator(tasks, unified, mock(TokenResolver.class), mock(McpAdapter.class),
                feishu, audit, traces, new ControlCommandRouter(), 20);
    }

    private Map<String, Object> task(String question) {
        return Map.of("message_text", question, "feishu_message_id", "m1", "feishu_chat_id", "c1",
                "feishu_open_id", "u1", "chat_type", "p2p");
    }

    private UnifiedQueryService.QueryResult result(String path, String answer) {
        AnswerPresentation presentation = new AnswerPresentation(answer,
                List.of(AnswerPresentation.ContentBlock.markdown("## " + answer)));
        String json = "{\"plainText\":\"" + answer + "\"}";
        return new UnifiedQueryService.QueryResult(path, answer, presentation, json, "deepseek-v4-pro",
                1, 2, 2, 2, 0, 0, List.of("query_tasks"), List.of("P1"), List.of(), null, 20, 10, 100);
    }
}
