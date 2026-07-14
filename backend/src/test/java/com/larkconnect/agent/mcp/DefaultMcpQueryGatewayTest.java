package com.larkconnect.agent.mcp;

import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.audit.TraceEventService;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

class DefaultMcpQueryGatewayTest {
    @Test
    void exposesProjectSpecificAliasesWhenCandidateProjectsUseDifferentSchemas() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "一", null, "token-1"),
                new TokenResolver.TokenEntry(2L, "P2", "二", null, "token-2"))));
        when(adapter.listTools("token-1")).thenReturn(toolList("string"));
        when(adapter.listTools("token-2")).thenReturn(toolList("integer"));

        McpQueryGateway.QueryContext context = gateway(resolver, adapter).loadContext("u1", "c1", "p2p");

        assertThat(context.availableTools()).hasSize(2);
        assertThat(context.availableTools()).extracting(tool -> tool.get("supportedProjectIds"))
                .containsExactly(List.of("P1"), List.of("P2"));
        assertThat(context.availabilityError()).isNull();
    }

    @Test
    void recordsToolDiscoveryAndBusinessCallWithCountsAndTraceIds() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        TraceEventService traces = mock(TraceEventService.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        Map<String, Object> tools = toolList("string");
        when(adapter.listToolsObserved("token-1")).thenReturn(new McpAdapter.ObservedCall(
                Map.of("method", "tools/list"), tools, 12, 1, null));
        when(adapter.callToolObserved("token-1", "query_tasks", Map.of(
                "status", "open", "project_id", "P1", "primelayer_user_id", "pl")))
                .thenReturn(new McpAdapter.ObservedCall(
                        Map.of("method", "tools/call"), Map.of("result", Map.of("items", List.of(1, 2, 3))),
                        20, 3, null));
        TraceEventService.EventHandle discoveryHandle = new TraceEventService.EventHandle("discovery-1", "r1", 1, true);
        TraceEventService.EventHandle validationHandle = new TraceEventService.EventHandle("validation-1", "r1", 1, true);
        TraceEventService.EventHandle toolHandle = new TraceEventService.EventHandle("tool-1", "r1", 1, true);
        when(traces.start(any())).thenReturn(discoveryHandle, validationHandle, toolHandle);
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter, traces);

        McpQueryGateway.QueryContext context = gateway.loadContext("r1", "u1", "c1", "p2p");
        List<McpQueryGateway.ToolObservation> observations = gateway.execute(
                "r1", context, "query_tasks", List.of("P1"), Map.of("status", "open"),
                new McpQueryGateway.ExecutionTrace("model-1", List.of("model-1"), 1, "logical-1"));

        assertThat(context.traceEventIds()).containsExactly("discovery-1");
        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.returnedCount()).isEqualTo(3);
            assertThat(observation.reportedTotalCount()).isNull();
            assertThat(observation.traceEventIds()).containsExactly("tool-1");
        });
        verify(traces, times(3)).complete(any(), any());
    }

    @Test
    void injectsServerManagedArgumentsBeforeSchemaValidation() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        when(adapter.listTools("token-1")).thenReturn(Map.of("result", Map.of("tools", List.of(Map.of(
                "name", "query_tasks", "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("status", "project_id", "primelayer_user_id"),
                        "properties", Map.of(
                                "status", Map.of("type", "string"),
                                "project_id", Map.of("type", "string"),
                                "primelayer_user_id", Map.of("type", "string"))))))));
        when(adapter.callToolObserved(eq("token-1"), eq("query_tasks"), any()))
                .thenReturn(new McpAdapter.ObservedCall(Map.of(), Map.of("result", Map.of()), 5, 0, 0));
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter);

        McpQueryGateway.QueryContext context = gateway.loadContext("u1", "c1", "p2p");
        McpQueryGateway.ToolObservation observation = gateway.execute(
                "r-managed", context, "query_tasks", List.of("P1"), Map.of("status", "open")).get(0);

        assertThat(observation.status()).isEqualTo("SUCCEEDED");
        verify(adapter).callToolObserved("token-1", "query_tasks", Map.of(
                "status", "open", "project_id", "P1", "primelayer_user_id", "pl"));
    }

    @Test
    void exposesFormCandidatesEmbeddedInMcpTextContent() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        when(adapter.listTools("token-1")).thenReturn(Map.of("result", Map.of("tools", List.of(Map.of(
                "name", "match_form_resource", "inputSchema", Map.of(
                        "type", "object", "required", List.of("name"),
                        "properties", Map.of("name", Map.of("type", "string"))))))));
        Map<String, Object> raw = Map.of("result", Map.of("content", List.of(Map.of(
                "type", "text", "text", "{\"forms\":[{\"formId\":\"F-1\",\"name\":\"安全隐患\"}]}"))));
        when(adapter.callToolObserved(eq("token-1"), eq("match_form_resource"), any()))
                .thenReturn(new McpAdapter.ObservedCall(Map.of(), raw, 5, 1, null));
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter);

        McpQueryGateway.QueryContext context = gateway.loadContext("u1", "c1", "p2p");
        McpQueryGateway.ToolObservation observation = gateway.execute(
                "r-text", context, "match_form_resource", List.of("P1"), Map.of("name", "安全")).get(0);

        assertThat((List<?>) observation.payload().get("records")).singleElement()
                .satisfies(item -> assertThat(String.valueOf(((Map<?, ?>) item).get("formId")))
                        .isEqualTo("F-1"));
        assertThat(observation.payload().get("businessPayload")).isInstanceOf(Map.class);
    }

    @Test
    void exposesNormalizedRecordsAndMarksIncompletePaginationPartial() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        when(adapter.listTools("token-1")).thenReturn(paginatedToolList());
        McpAdapter.PageData page = new McpAdapter.PageData(0, 100, "SUCCEEDED",
                Map.of("page", 1), Map.of("result", "raw"), null, 10, 2, 5);
        McpAdapter.PaginationResult pagination = new McpAdapter.PaginationResult(
                List.of(page), 5, 1, 1, 0, false,
                2, 5, false, "empty_page_before_total", List.of(Map.of("id", 1), Map.of("id", 2)));
        when(adapter.callToolWithPaginationBatch(eq("token-1"), eq("query_form_data_list"), any(),
                eq(100), eq(McpAdapter.PaginationStyle.PAGE_SIZE), any(), eq(5), any()))
                .thenReturn(new McpAdapter.PaginationBatchResult(pagination, null, true));
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter);

        McpQueryGateway.QueryContext context = gateway.loadContext("u1", "c1", "p2p");
        McpQueryGateway.ToolObservation observation = gateway.execute(
                "r-partial", context, "query_form_data_list", List.of("P1"), Map.of()).get(0);

        assertThat(observation.status()).isEqualTo("PARTIAL");
        assertThat(observation.error()).contains("2/5");
        assertThat(observation.payload()).containsEntry("fetchedCount", 2)
                .containsEntry("reportedTotalCount", 5)
                .containsEntry("coverageComplete", false);
        assertThat((List<?>) observation.payload().get("evidenceSamples")).hasSize(2);
        assertThat(observation.payload().get("statistics")).isNotNull();
    }

    @Test
    void marksFivePageBatchPendingAndExposesSerializableContinuation() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        when(adapter.listTools("token-1")).thenReturn(paginatedToolList());
        McpAdapter.PaginationResult pagination = new McpAdapter.PaginationResult(
                List.of(), 1000, 5, 5, 0, false, 500, 1000, false, null, List.of());
        McpAdapter.PaginationContinuation continuation = new McpAdapter.PaginationContinuation(
                5, null, List.of("h1"), 5, 0, Map.of("fetchedCount", 500));
        when(adapter.callToolWithPaginationBatch(eq("token-1"), eq("query_form_data_list"), any(),
                eq(100), eq(McpAdapter.PaginationStyle.PAGE_SIZE), any(), eq(5), any()))
                .thenReturn(new McpAdapter.PaginationBatchResult(pagination, continuation, false));
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter);

        McpQueryGateway.QueryContext context = gateway.loadContext("u1", "c1", "p2p");
        McpQueryGateway.ToolObservation observation = gateway.execute(
                "r-batch", context, "query_form_data_list", List.of("P1"), Map.of()).get(0);

        assertThat(observation.status()).isEqualTo("PENDING");
        assertThat(observation.payload()).containsEntry("asyncTaskTerminal", false)
                .containsKey("paginationState");
    }

    @Test
    void resumesPaginationStateWithoutRevalidatingInternalArguments() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        when(adapter.listTools("token-1")).thenReturn(paginatedToolList());
        McpAdapter.PaginationResult partial = new McpAdapter.PaginationResult(
                List.of(), 1000, 5, 5, 0, false, 500, 1000, false, null, List.of());
        McpAdapter.PaginationContinuation continuation = new McpAdapter.PaginationContinuation(
                5, null, List.of("h1"), 5, 0, Map.of("fetchedCount", 500));
        McpAdapter.PaginationResult complete = new McpAdapter.PaginationResult(
                List.of(), 1000, 10, 10, 0, false, 1000, 1000, true, null, List.of());
        when(adapter.callToolWithPaginationBatch(eq("token-1"), eq("query_form_data_list"), any(),
                eq(100), eq(McpAdapter.PaginationStyle.PAGE_SIZE), any(), eq(5), any()))
                .thenReturn(new McpAdapter.PaginationBatchResult(partial, continuation, false),
                        new McpAdapter.PaginationBatchResult(complete, null, true));
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter);
        McpQueryGateway.QueryContext context = gateway.loadContext("u1", "c1", "p2p");

        McpQueryGateway.ToolObservation first = gateway.execute(
                "r-resume-page", context, "query_form_data_list", List.of("P1"), Map.of()).get(0);
        Map<String, Object> resumeArgs = Map.of("_paginationStates",
                Map.of("P1", first.payload().get("paginationState")));
        McpQueryGateway.ToolObservation second = gateway.execute(
                "r-resume-page", context, "query_form_data_list", List.of("P1"), resumeArgs).get(0);

        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(second.payload()).containsEntry("fetchedCount", 1000)
                .containsEntry("coverageComplete", true);
        ArgumentCaptor<McpAdapter.PaginationContinuation> captor =
                ArgumentCaptor.forClass(McpAdapter.PaginationContinuation.class);
        verify(adapter, times(2)).callToolWithPaginationBatch(eq("token-1"), eq("query_form_data_list"),
                any(), eq(100), eq(McpAdapter.PaginationStyle.PAGE_SIZE), any(), eq(5), captor.capture());
        assertThat(captor.getAllValues().get(1).nextPage()).isEqualTo(5);
    }

    @Test
    void turnsNetworkFailureIntoRecoverableContinuation() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "项目一", null, "token-1"))));
        when(adapter.listTools("token-1")).thenReturn(toolList("string"));
        when(adapter.callToolObserved(eq("token-1"), eq("query_tasks"), any()))
                .thenThrow(new ResourceAccessException("connection reset"));
        DefaultMcpQueryGateway gateway = gateway(resolver, adapter);

        McpQueryGateway.QueryContext context = gateway.loadContext("u1", "c1", "p2p");
        McpQueryGateway.ToolObservation observation = gateway.execute(
                "r-network", context, "query_tasks", List.of("P1"), Map.of("status", "open")).get(0);

        assertThat(observation.status()).isEqualTo("RETRYING");
        assertThat(observation.payload()).containsEntry("retryable", true)
                .containsEntry("asyncTaskTerminal", false);
        assertThat(observation.error()).isNull();
    }

    private DefaultMcpQueryGateway gateway(TokenResolver resolver, McpAdapter adapter) {
        return gateway(resolver, adapter, null);
    }

    private DefaultMcpQueryGateway gateway(TokenResolver resolver, McpAdapter adapter, TraceEventService traces) {
        AppProperties properties = new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(20, 30000, 30000),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("https://api.deepseek.com", "key"),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key"));
        return new DefaultMcpQueryGateway(resolver, adapter, new McpToolRegistry(), mock(AuditService.class), properties, traces);
    }

    private Map<String, Object> toolList(String statusType) {
        return Map.of("result", Map.of("tools", List.of(Map.of(
                "name", "query_tasks",
                "inputSchema", Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                "status", Map.of("type", statusType)))))));
    }

    private Map<String, Object> paginatedToolList() {
        return Map.of("result", Map.of("tools", List.of(Map.of(
                "name", "query_form_data_list",
                "inputSchema", Map.of("type", "object", "properties", Map.of(
                        "page", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer")))))));
    }
}
