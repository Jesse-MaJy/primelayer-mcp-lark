package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChainTraceService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TraceEventService traceEvents;

    public ChainTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                             TraceEventService traceEvents) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.traceEvents = traceEvents;
    }

    public void save(String requestId, ChainTrace trace) {
        jdbcTemplate.update(
                "insert into agent_chain_trace(request_id, trace_data) values (?, cast(? as json)) on duplicate key update trace_data = values(trace_data)",
                requestId, toJson(trace.toMap())
        );
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> load(String requestId) {
        Optional<Map<String, Object>> eventTrace = traceEvents.loadTrace(requestId);
        if (eventTrace.isPresent()) return eventTrace.map(trace -> applyPersistedCompleteness(requestId, trace));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select trace_data from agent_chain_trace where request_id = ?", requestId
        );
        if (rows.isEmpty()) return Optional.empty();
        Object data = rows.get(0).get("trace_data");
        if (data instanceof Map<?, ?> m) {
            return Optional.of(asLegacy((Map<String, Object>) m));
        }
        try {
            return Optional.of(asLegacy(objectMapper.readValue(String.valueOf(data), Map.class)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> loadEvent(String requestId, String eventId) {
        return traceEvents.loadEvent(requestId, eventId);
    }

    public boolean eventTraceIncomplete(String requestId) {
        return traceEvents.isIncomplete(requestId);
    }

    private Map<String, Object> asLegacy(Map<String, Object> stored) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(stored);
        result.put("legacy", true);
        result.put("completeness", "SUMMARY_ONLY");
        result.put("traceCompleteness", "SUMMARY_ONLY");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyPersistedCompleteness(String requestId, Map<String, Object> trace) {
        List<String> statuses = jdbcTemplate.queryForList(
                "select trace_status from agent_audit_log where request_id = ?", String.class, requestId);
        if (statuses.stream().noneMatch("PARTIAL"::equals)) return trace;
        Map<String, Object> result = new java.util.LinkedHashMap<>(trace);
        result.put("completeness", "PARTIAL");
        result.put("traceCompleteness", "PARTIAL");
        Map<String, Object> summary = new java.util.LinkedHashMap<>((Map<String, Object>)
                trace.getOrDefault("summary", Map.of()));
        summary.put("traceIncomplete", true);
        summary.put("traceCompleteness", "PARTIAL");
        result.put("summary", summary);
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
