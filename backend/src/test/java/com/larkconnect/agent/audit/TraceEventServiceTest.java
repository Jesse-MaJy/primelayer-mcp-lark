package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceEventServiceTest {
    private TraceEventService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:trace;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists agent_trace_event");
        jdbc.execute("""
                create table agent_trace_event (
                  id bigint auto_increment primary key,
                  event_id varchar(64) not null unique,
                  request_id varchar(128) not null,
                  sequence_no int not null,
                  parent_event_id varchar(64), dependency_event_ids clob,
                  round_index int, event_type varchar(64) not null, purpose varchar(64), label varchar(256),
                  status varchar(32) not null, project_id varchar(128), project_name varchar(256),
                  tool_name varchar(256), model_name varchar(128), page_index int, page_size int,
                  returned_count int, reported_total_count int,
                  input_tokens int default 0, output_tokens int default 0, total_tokens int default 0,
                  started_at timestamp, finished_at timestamp, latency_ms bigint,
                  input_json clob, output_json clob, usage_json clob, error_json clob, metadata_json clob
                )
                """);
        jdbc.execute("drop table if exists agent_query_checkpoint");
        jdbc.execute("create table agent_query_checkpoint(request_id varchar primary key, phase varchar, version bigint, state_json clob, recovered_after_restart boolean)");
        jdbc.execute("drop table if exists agent_delivery_outbox");
        jdbc.execute("create table agent_delivery_outbox(request_id varchar, delivery_type varchar, status varchar, attempts int)");
        jdbc.execute("drop table if exists agent_task");
        jdbc.execute("create table agent_task(request_id varchar primary key, status varchar)");
        service = new TraceEventService(jdbc, new ObjectMapper(), new TraceRedactor());
    }

    @Test
    void persistsRunningEventThenCompletesItWithSanitizedPayloadAndUsage() {
        TraceEventService.EventHandle handle = service.start(TraceEventService.EventStart.builder("r1", "model_call")
                .purpose("decision")
                .label("DeepSeek 决策 第 1 轮")
                .roundIndex(1)
                .modelName("deepseek-v4-pro")
                .input(Map.of("authorization", "Bearer secret", "question", "质量情况"))
                .build());

        service.complete(handle, TraceEventService.EventCompletion.succeeded()
                .output(Map.of("answer", "完成"))
                .usage(Map.of("prompt_tokens", 12, "completion_tokens", 3))
                .tokens(12, 3)
                .build());

        Map<String, Object> detail = service.loadEvent("r1", handle.eventId()).orElseThrow();
        assertThat(detail).containsEntry("status", "SUCCEEDED")
                .containsEntry("inputTokens", 12)
                .containsEntry("outputTokens", 3)
                .containsEntry("totalTokens", 15);
        assertThat(detail.get("input").toString()).contains("***REDACTED***").doesNotContain("Bearer secret");
        assertThat(detail.get("output").toString()).contains("完成");
        assertThat(((Number) detail.get("latencyMs")).longValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void buildsSummaryWithoutReturningLargePayloadsAndPreservesDependencies() {
        TraceEventService.EventHandle decision = service.start(TraceEventService.EventStart.builder("r2", "model_call")
                .purpose("decision").label("决策").build());
        service.complete(decision, TraceEventService.EventCompletion.succeeded().tokens(10, 5).build());
        TraceEventService.EventHandle tool = service.start(TraceEventService.EventStart.builder("r2", "mcp_call")
                .purpose("tool_call").label("query_form_data_list")
                .parentEventId(decision.eventId()).dependencyEventIds(List.of(decision.eventId()))
                .toolName("query_form_data_list").returnedCount(23).input(Map.of("large", "payload"))
                .build());
        service.complete(tool, TraceEventService.EventCompletion.succeeded().returnedCount(23).build());

        Map<String, Object> trace = service.loadTrace("r2").orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) trace.get("edges");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");

        assertThat(nodes).hasSize(2).allSatisfy(node -> {
            assertThat(node).doesNotContainKeys("input", "output", "usage", "error");
        });
        assertThat(edges).anySatisfy(edge -> assertThat(edge)
                .containsEntry("from", decision.eventId())
                .containsEntry("to", tool.eventId()));
        assertThat(summary).containsEntry("modelCallCount", 1)
                .containsEntry("businessPhysicalCalls", 1)
                .containsEntry("returnedCount", 23)
                .containsEntry("inputTokens", 10)
                .containsEntry("outputTokens", 5)
                .containsEntry("totalTokens", 15);
    }

    @Test
    void groupsTokensByModelPurposeAndCountsDistinctCacheHits() {
        addModelEvent("r-metrics", "decision", 10, 2, Map.of("messages", List.of()));
        addModelEvent("r-metrics", "chunk_analysis", 100, 5, Map.of("messages", List.of()));
        addModelEvent("r-metrics", "final_answer", 20, 4, Map.of("messages", List.of(
                Map.of("role", "tool", "content", "{\"cacheHit\":true,\"cacheHitId\":1,\"reusedCallSignature\":\"sig-1\"}"))));
        addModelEvent("r-metrics", "presentation", 8, 3, Map.of("messages", List.of(
                Map.of("role", "tool", "content", "{\"cacheHit\":true,\"cacheHitId\":1,\"reusedCallSignature\":\"sig-1\"}"))));

        Map<String, Object> trace = service.loadTrace("r-metrics").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> breakdown =
                (Map<String, Map<String, Object>>) summary.get("tokenBreakdown");

        assertThat(breakdown.get("decision")).containsEntry("totalTokens", 12);
        assertThat(breakdown.get("chunk_analysis")).containsEntry("totalTokens", 105);
        assertThat(breakdown.get("final_answer")).containsEntry("totalTokens", 24);
        assertThat(breakdown.get("presentation")).containsEntry("totalTokens", 11);
        assertThat(summary).containsEntry("cacheHitCount", 1).containsEntry("savedMcpCalls", 1);
    }

    @Test
    void addsRecoverableLifecycleAndDeliveryMetricsToSummary() {
        addModelEvent("r-life", "decision", 1, 1, Map.of());
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:trace;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", ""));
        jdbc.update("insert into agent_query_checkpoint values (?,?,?,?,?)", "r-life", "POLLING_ASYNC", 7,
                "{\"pollAttempts\":4,\"retryCount\":1,\"stopReason\":\"waiting\"}", true);
        jdbc.update("insert into agent_delivery_outbox values (?,?,?,?)", "r-life", "LONG_RUNNING_NOTICE", "SENT", 1);

        Map<String, Object> summary = (Map<String, Object>) service.loadTrace("r-life").orElseThrow().get("summary");

        assertThat(summary).containsEntry("phase", "POLLING_ASYNC")
                .containsEntry("checkpointVersion", 7)
                .containsEntry("pollAttempts", 4)
                .containsEntry("retryCount", 1)
                .containsEntry("recoveredAfterRestart", true)
                .containsEntry("longRunningNoticeSent", true);
    }

    @Test
    void exposesBusinessStatusSeparatelyFromTraceCompletenessAndStageMetadata() {
        TraceEventService.EventHandle handle = service.start(TraceEventService.EventStart.builder(
                        "r-status", "model_call")
                .purpose("form_analysis").label("表单分析")
                .metadata(Map.of("stage", "ANALYZING_FORMS", "formId", "FORM-1", "jobId", "job-1"))
                .build());
        service.complete(handle, TraceEventService.EventCompletion.succeeded().build());
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:trace;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", ""));
        jdbc.update("insert into agent_task values (?,?)", "r-status", "PARTIAL");

        Map<String, Object> trace = service.loadTrace("r-status").orElseThrow();
        assertThat(trace).containsEntry("executionStatus", "PARTIAL")
                .containsEntry("traceCompleteness", "COMPLETE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");
        assertThat(nodes.get(0)).containsEntry("stage", "ANALYZING_FORMS")
                .containsEntry("formId", "FORM-1").containsEntry("jobId", "job-1");
    }

    @Test
    void exposesCumulativePaginationMetadataAndExcludesDuplicatePagesFromFetchedSummary() {
        TraceEventService.EventHandle first = service.start(TraceEventService.EventStart.builder(
                        "r-pages", "mcp_call")
                .purpose("tool_call").label("query_form_data_list · 第 1 页")
                .toolName("query_form_data_list").pageIndex(1).pageSize(100)
                .metadata(Map.of("formId", "FORM-1", "formName", "质量检查",
                        "logicalCallId", "job-1"))
                .build());
        service.complete(first, TraceEventService.EventCompletion.succeeded()
                .returnedCount(100).reportedTotalCount(2399)
                .metadata(Map.of("formId", "FORM-1", "formName", "质量检查",
                        "logicalCallId", "job-1", "cumulativeFetchedCount", 100,
                        "coveragePercent", 4.2, "duplicatePage", false))
                .build());
        TraceEventService.EventHandle duplicate = service.start(TraceEventService.EventStart.builder(
                        "r-pages", "mcp_call")
                .purpose("tool_call").label("query_form_data_list · 第 2 页")
                .toolName("query_form_data_list").pageIndex(2).pageSize(100)
                .build());
        service.complete(duplicate, TraceEventService.EventCompletion.succeeded()
                .returnedCount(100).reportedTotalCount(2399)
                .metadata(Map.of("formId", "FORM-1", "formName", "质量检查",
                        "logicalCallId", "job-1", "cumulativeFetchedCount", 100,
                        "coveragePercent", 4.2, "duplicatePage", true))
                .build());

        Map<String, Object> trace = service.loadTrace("r-pages").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) trace.get("nodes");

        assertThat(summary).containsEntry("returnedCount", 100)
                .containsEntry("businessPhysicalCalls", 2);
        assertThat(nodes.get(0)).containsEntry("formId", "FORM-1")
                .containsEntry("formName", "质量检查")
                .containsEntry("cumulativeFetchedCount", 100)
                .containsEntry("coveragePercent", 4.2)
                .containsEntry("duplicatePage", false);
        assertThat(nodes.get(1)).containsEntry("duplicatePage", true);
    }

    private void addModelEvent(String requestId, String purpose, int inputTokens, int outputTokens,
                               Map<String, Object> input) {
        TraceEventService.EventHandle handle = service.start(TraceEventService.EventStart.builder(requestId, "model_call")
                .purpose(purpose).label(purpose).input(input).build());
        service.complete(handle, TraceEventService.EventCompletion.succeeded()
                .tokens(inputTokens, outputTokens).build());
    }
}
