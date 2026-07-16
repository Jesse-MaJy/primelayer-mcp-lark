package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainTraceServiceTest {
    @Test
    void prefersNewEventTraceAndLoadsNodeDetail() {
        JdbcTemplate jdbc = jdbc("new_trace");
        TraceEventService events = mock(TraceEventService.class);
        Map<String, Object> eventTrace = Map.of("requestId", "r1", "legacy", false,
                "completeness", "COMPLETE", "nodes", List.of(), "edges", List.of(), "summary", Map.of());
        when(events.loadTrace("r1")).thenReturn(Optional.of(eventTrace));
        when(events.loadEvent("r1", "e1")).thenReturn(Optional.of(Map.of("eventId", "e1", "input", Map.of("q", 1))));
        ChainTraceService service = new ChainTraceService(jdbc, new ObjectMapper(), events);

        assertThat(service.load("r1")).contains(eventTrace);
        assertThat(service.loadEvent("r1", "e1")).hasValueSatisfying(detail ->
                assertThat(detail).containsEntry("eventId", "e1"));
    }

    @Test
    void restoresPersistedPartialCompletenessAfterRestart() {
        JdbcTemplate jdbc = jdbc("partial_trace");
        jdbc.update("insert into agent_audit_log(request_id, trace_status) values (?, ?)", "r1", "PARTIAL");
        TraceEventService events = mock(TraceEventService.class);
        when(events.loadTrace("r1")).thenReturn(Optional.of(Map.of(
                "requestId", "r1", "legacy", false, "completeness", "COMPLETE",
                "nodes", List.of(), "edges", List.of(), "summary", Map.of("traceIncomplete", false))));
        ChainTraceService service = new ChainTraceService(jdbc, new ObjectMapper(), events);

        Map<String, Object> trace = service.load("r1").orElseThrow();

        assertThat(trace).containsEntry("completeness", "PARTIAL");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
        assertThat(summary).containsEntry("traceIncomplete", true);
    }

    @Test
    void wrapsLegacyJsonWithExplicitCompletenessMarker() {
        JdbcTemplate jdbc = jdbc("legacy_trace");
        jdbc.update("insert into agent_chain_trace(request_id, trace_data) values (?, ?)",
                "old", "{\"requestId\":\"old\",\"nodes\":[],\"edges\":[],\"summary\":{\"totalMcpCalls\":2}}");
        TraceEventService events = mock(TraceEventService.class);
        when(events.loadTrace("old")).thenReturn(Optional.empty());
        ChainTraceService service = new ChainTraceService(jdbc, new ObjectMapper(), events);

        Map<String, Object> trace = service.load("old").orElseThrow();

        assertThat(trace).containsEntry("legacy", true).containsEntry("completeness", "SUMMARY_ONLY");
        assertThat(trace.get("nodes")).isEqualTo(List.of());
    }

    private JdbcTemplate jdbc(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table agent_chain_trace (id bigint auto_increment primary key, request_id varchar(128) unique, trace_data clob)");
        jdbc.execute("create table agent_audit_log (request_id varchar(128) primary key, trace_status varchar(32))");
        return jdbc;
    }
}
