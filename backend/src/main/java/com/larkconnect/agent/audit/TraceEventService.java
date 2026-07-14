package com.larkconnect.agent.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.common.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Clob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TraceEventService {
    private static final Logger log = LoggerFactory.getLogger(TraceEventService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TraceRedactor redactor;
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final Set<String> incompleteRequests = ConcurrentHashMap.newKeySet();

    public TraceEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, TraceRedactor redactor) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
    }

    public EventHandle start(EventStart event) {
        String eventId = UUID.randomUUID().toString();
        long startedNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        try {
            int sequence = sequences.computeIfAbsent(event.requestId, ignored -> new AtomicInteger()).incrementAndGet();
            jdbcTemplate.update("""
                    insert into agent_trace_event(
                      event_id, request_id, sequence_no, parent_event_id, dependency_event_ids,
                      round_index, event_type, purpose, label, status, project_id, project_name,
                      tool_name, model_name, page_index, page_size, returned_count, reported_total_count,
                      started_at, input_json, metadata_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, eventId, event.requestId, sequence, event.parentEventId, toJson(event.dependencyEventIds),
                    event.roundIndex, event.eventType, event.purpose, event.label, Status.RUNNING,
                    event.projectId, event.projectName, event.toolName, event.modelName,
                    event.pageIndex, event.pageSize, event.returnedCount, event.reportedTotalCount,
                    Timestamp.from(startedAt), toJson(redactor.redact(event.input)), toJson(redactor.redact(event.metadata)));
            return new EventHandle(eventId, event.requestId, startedNanos, true);
        } catch (Exception e) {
            markIncomplete(event.requestId, e);
            return new EventHandle(eventId, event.requestId, startedNanos, false);
        }
    }

    public void complete(EventHandle handle, EventCompletion completion) {
        if (handle == null || !handle.persisted) return;
        long latencyMs = Math.max(0, (System.nanoTime() - handle.startedNanos) / 1_000_000);
        try {
            jdbcTemplate.update("""
                    update agent_trace_event set status = ?, purpose = coalesce(?, purpose), label = coalesce(?, label),
                      finished_at = ?, latency_ms = ?, input_json = coalesce(?, input_json),
                      returned_count = coalesce(?, returned_count), reported_total_count = coalesce(?, reported_total_count),
                      input_tokens = ?, output_tokens = ?, total_tokens = ?, output_json = ?, usage_json = ?,
                      error_json = ?, metadata_json = coalesce(?, metadata_json)
                    where event_id = ? and request_id = ?
                    """, completion.status, completion.purpose, completion.label,
                    Timestamp.from(Instant.now()), latencyMs,
                    completion.input == null ? null : toJson(redactor.redact(completion.input)),
                    completion.returnedCount, completion.reportedTotalCount,
                    completion.inputTokens, completion.outputTokens, completion.inputTokens + completion.outputTokens,
                    toJson(redactor.redact(completion.output)), toJson(redactor.redact(completion.usage)),
                    toJson(redactor.redact(completion.error)),
                    completion.metadata == null ? null : toJson(redactor.redact(completion.metadata)),
                    handle.eventId, handle.requestId);
        } catch (Exception e) {
            markIncomplete(handle.requestId, e);
        }
    }

    public void fail(EventHandle handle, Throwable error) {
        complete(handle, EventCompletion.failed().error(Map.of("message", readable(error))).build());
    }

    public boolean isIncomplete(String requestId) {
        return incompleteRequests.contains(requestId);
    }

    public Optional<Map<String, Object>> loadTrace(String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select event_id, sequence_no, parent_event_id, dependency_event_ids, round_index,
                       event_type, purpose, label, status, project_id, project_name, tool_name, model_name,
                       page_index, page_size, returned_count, reported_total_count,
                       input_tokens, output_tokens, total_tokens, started_at, finished_at, latency_ms,
                       input_json, metadata_json
                from agent_trace_event where request_id = ? order by sequence_no, id
                """, requestId);
        if (rows.isEmpty()) return Optional.empty();

        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<Map<String, String>> edges = new LinkedHashSet<>();
        int modelCalls = 0;
        int discoveryCalls = 0;
        int businessPhysicalCalls = 0;
        int totalMcpRequests = 0;
        int returnedCount = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        Instant first = null;
        Instant last = null;
        Set<String> logicalCallIds = new LinkedHashSet<>();
        Set<String> cacheHitSignatures = new LinkedHashSet<>();
        Map<String, int[]> tokenBreakdown = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            Map<String, Object> node = nodeSummary(row);
            nodes.add(node);
            String eventId = text(row, "event_id");
            List<String> dependencies = stringList(parse(row.get("dependency_event_ids")));
            if (dependencies.isEmpty() && row.get("parent_event_id") != null) {
                dependencies = List.of(text(row, "parent_event_id"));
            }
            for (String dependency : dependencies) {
                if (!dependency.isBlank()) edges.add(Map.of("from", dependency, "to", eventId));
            }
            String eventType = text(row, "event_type");
            String purpose = text(row, "purpose");
            Map<String, Object> metadata = objectMap(parse(row.get("metadata_json")));
            if ("model_call".equals(eventType)) {
                modelCalls++;
                int[] tokens = tokenBreakdown.computeIfAbsent(
                        purpose.isBlank() ? "other" : purpose, ignored -> new int[2]);
                tokens[0] += number(row.get("input_tokens"));
                tokens[1] += number(row.get("output_tokens"));
                collectCacheHitSignatures(parse(row.get("input_json")), cacheHitSignatures);
            }
            if ("mcp_call".equals(eventType)) {
                totalMcpRequests++;
                if ("tool_discovery".equals(purpose)) discoveryCalls++;
                if ("tool_call".equals(purpose)) {
                    businessPhysicalCalls++;
                    if (!Boolean.TRUE.equals(metadata.get("duplicatePage"))) {
                        returnedCount += number(row.get("returned_count"));
                    }
                }
            }
            inputTokens += number(row.get("input_tokens"));
            outputTokens += number(row.get("output_tokens"));
            Object logicalCallId = metadata.get("logicalCallId");
            if (logicalCallId != null) logicalCallIds.add(String.valueOf(logicalCallId));
            Instant started = instant(row.get("started_at"));
            Instant finished = instant(row.get("finished_at"));
            if (started != null && (first == null || started.isBefore(first))) first = started;
            if (finished != null && (last == null || finished.isAfter(last))) last = finished;
        }
        long processingLatency = first == null || last == null ? 0 : Math.max(0, Duration.between(first, last).toMillis());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("modelCallCount", modelCalls);
        summary.put("toolDiscoveryCalls", discoveryCalls);
        summary.put("businessLogicalCalls", logicalCallIds.size());
        summary.put("businessPhysicalCalls", businessPhysicalCalls);
        summary.put("totalMcpRequests", totalMcpRequests);
        summary.put("returnedCount", returnedCount);
        summary.put("inputTokens", inputTokens);
        summary.put("outputTokens", outputTokens);
        summary.put("totalTokens", inputTokens + outputTokens);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        tokenBreakdown.forEach((purpose, tokens) -> breakdown.put(purpose, Map.of(
                "inputTokens", tokens[0],
                "outputTokens", tokens[1],
                "totalTokens", tokens[0] + tokens[1])));
        summary.put("tokenBreakdown", breakdown);
        summary.put("cacheHitCount", cacheHitSignatures.size());
        summary.put("savedMcpCalls", cacheHitSignatures.size());
        summary.put("processingLatencyMs", processingLatency);
        summary.put("traceIncomplete", isIncomplete(requestId));
        appendLifecycleSummary(requestId, summary);
        String traceCompleteness = isIncomplete(requestId) ? "PARTIAL" : "COMPLETE";
        String executionStatus = summary.containsKey("taskStatus")
                ? normalizeExecutionStatus(summary.get("taskStatus")) : executionStatus(summary.get("phase"));
        summary.put("traceCompleteness", traceCompleteness);
        summary.put("executionStatus", executionStatus);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("legacy", false);
        result.put("completeness", traceCompleteness);
        result.put("traceCompleteness", traceCompleteness);
        result.put("executionStatus", executionStatus);
        result.put("summary", summary);
        result.put("nodes", nodes);
        result.put("edges", List.copyOf(edges));
        return Optional.of(result);
    }

    private void appendLifecycleSummary(String requestId, Map<String, Object> summary) {
        try {
            List<Map<String, Object>> checkpoints = jdbcTemplate.queryForList(
                    "select phase, version, state_json, recovered_after_restart from agent_query_checkpoint where request_id=?",
                    requestId);
            if (!checkpoints.isEmpty()) {
                Map<String, Object> checkpoint = checkpoints.get(0);
                summary.put("phase", checkpoint.get("phase"));
                summary.put("checkpointVersion", number(checkpoint.get("version")));
                summary.put("recoveredAfterRestart", Boolean.TRUE.equals(checkpoint.get("recovered_after_restart"))
                        || number(checkpoint.get("recovered_after_restart")) == 1);
                Map<String, Object> state = objectMap(parse(checkpoint.get("state_json")));
                for (String key : List.of("pollAttempts", "retryCount", "asyncStatusTransitions", "stopReason")) {
                    if (state.containsKey(key)) summary.put(key, state.get(key));
                }
            }
            Integer notices = jdbcTemplate.queryForObject("""
                    select count(*) from agent_delivery_outbox where request_id=?
                      and delivery_type='LONG_RUNNING_NOTICE' and status='SENT'
                    """, Integer.class, requestId);
            summary.put("longRunningNoticeSent", notices != null && notices > 0);
            List<String> taskStatuses = jdbcTemplate.queryForList(
                    "select status from agent_task where request_id=?", String.class, requestId);
            if (!taskStatuses.isEmpty()) summary.put("taskStatus", taskStatuses.get(0));
        } catch (Exception ignored) {
            // 兼容迁移前数据或精简测试数据库。
        }
    }

    public Optional<Map<String, Object>> loadEvent(String requestId, String eventId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from agent_trace_event where request_id = ? and event_id = ?", requestId, eventId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        Map<String, Object> detail = nodeSummary(row);
        detail.put("input", parse(row.get("input_json")));
        detail.put("output", parse(row.get("output_json")));
        detail.put("usage", parse(row.get("usage_json")));
        detail.put("error", parse(row.get("error_json")));
        detail.put("metadata", parse(row.get("metadata_json")));
        return Optional.of(detail);
    }

    private Map<String, Object> nodeSummary(Map<String, Object> row) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", text(row, "event_id"));
        node.put("eventId", text(row, "event_id"));
        node.put("sequence", number(row.get("sequence_no")));
        node.put("parentEventId", row.get("parent_event_id"));
        node.put("roundIndex", row.get("round_index"));
        node.put("type", text(row, "event_type"));
        node.put("purpose", row.get("purpose"));
        node.put("label", row.get("label"));
        node.put("status", row.get("status"));
        node.put("projectId", row.get("project_id"));
        node.put("projectName", row.get("project_name"));
        node.put("toolName", row.get("tool_name"));
        node.put("modelName", row.get("model_name"));
        node.put("pageIndex", row.get("page_index"));
        node.put("pageSize", row.get("page_size"));
        node.put("returnedCount", row.get("returned_count"));
        node.put("reportedTotalCount", row.get("reported_total_count"));
        node.put("inputTokens", number(row.get("input_tokens")));
        node.put("outputTokens", number(row.get("output_tokens")));
        node.put("totalTokens", number(row.get("total_tokens")));
        node.put("startedAt", row.get("started_at"));
        node.put("finishedAt", row.get("finished_at"));
        node.put("latencyMs", number(row.get("latency_ms")));
        Map<String, Object> metadata = objectMap(parse(row.get("metadata_json")));
        for (String key : List.of("stage", "formId", "formName", "jobId", "attempt", "logicalCallId",
                "cumulativeFetchedCount", "coveragePercent", "duplicatePage")) {
            if (metadata.containsKey(key)) node.put(key, metadata.get(key));
        }
        return node;
    }

    private String executionStatus(Object phaseValue) {
        String phase = phaseValue == null ? "" : String.valueOf(phaseValue);
        return switch (phase) {
            case "COMPLETED" -> "COMPLETED";
            case "PARTIAL" -> "PARTIAL";
            case "FAILED" -> "FAILED";
            default -> "RUNNING";
        };
    }

    private String normalizeExecutionStatus(Object statusValue) {
        String status = statusValue == null ? "RUNNING" : String.valueOf(statusValue);
        return "SUCCEEDED".equals(status) ? "COMPLETED" : status;
    }

    private void markIncomplete(String requestId, Exception e) {
        incompleteRequests.add(requestId);
        log.error("Failed to persist trace event for request {}", requestId, e);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("serializationError", readable(e)).toString();
        }
    }

    private Object parse(Object value) {
        if (value == null) return null;
        try {
            String json = value instanceof Clob clob ? clob.getSubString(1, (int) clob.length()) : String.valueOf(value);
            if (json.isBlank()) return null;
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectCacheHitSignatures(Object value, Set<String> signatures) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (Boolean.TRUE.equals(map.get("cacheHit"))) {
                Object signature = map.containsKey("cacheHitId")
                        ? "cache-hit-" + map.get("cacheHitId") : map.get("reusedCallSignature");
                if (signature != null) signatures.add(String.valueOf(signature));
            }
            map.values().forEach(nested -> collectCacheHitSignatures(nested, signatures));
        } else if (value instanceof List<?> list) {
            list.forEach(nested -> collectCacheHitSignatures(nested, signatures));
        } else if (value instanceof String text && text.contains("\"cacheHit\":true")) {
            try {
                collectCacheHitSignatures(objectMapper.readValue(text, Object.class), signatures);
            } catch (Exception ignored) {
                // Non-JSON prompt text cannot contribute a reliable cache-hit signature.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0; }
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        return null;
    }

    private String readable(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record EventHandle(String eventId, String requestId, long startedNanos, boolean persisted) {}

    public static final class EventStart {
        private final String requestId;
        private final String eventType;
        private String parentEventId;
        private List<String> dependencyEventIds = List.of();
        private Integer roundIndex;
        private String purpose;
        private String label;
        private String projectId;
        private String projectName;
        private String toolName;
        private String modelName;
        private Integer pageIndex;
        private Integer pageSize;
        private Integer returnedCount;
        private Integer reportedTotalCount;
        private Object input;
        private Object metadata;

        private EventStart(String requestId, String eventType) { this.requestId = requestId; this.eventType = eventType; }
        public static EventStart builder(String requestId, String eventType) { return new EventStart(requestId, eventType); }
        public EventStart parentEventId(String value) { parentEventId = value; return this; }
        public EventStart dependencyEventIds(List<String> value) { dependencyEventIds = value == null ? List.of() : List.copyOf(value); return this; }
        public EventStart roundIndex(Integer value) { roundIndex = value; return this; }
        public EventStart purpose(String value) { purpose = value; return this; }
        public EventStart label(String value) { label = value; return this; }
        public EventStart projectId(String value) { projectId = value; return this; }
        public EventStart projectName(String value) { projectName = value; return this; }
        public EventStart toolName(String value) { toolName = value; return this; }
        public EventStart modelName(String value) { modelName = value; return this; }
        public EventStart pageIndex(Integer value) { pageIndex = value; return this; }
        public EventStart pageSize(Integer value) { pageSize = value; return this; }
        public EventStart returnedCount(Integer value) { returnedCount = value; return this; }
        public EventStart reportedTotalCount(Integer value) { reportedTotalCount = value; return this; }
        public EventStart input(Object value) { input = value; return this; }
        public EventStart metadata(Object value) { metadata = value; return this; }
        public EventStart build() { return this; }
    }

    public static final class EventCompletion {
        private String status;
        private String purpose;
        private String label;
        private Integer returnedCount;
        private Integer reportedTotalCount;
        private int inputTokens;
        private int outputTokens;
        private Object output;
        private Object input;
        private Object usage;
        private Object error;
        private Object metadata;

        private EventCompletion(String status) { this.status = status; }
        public static EventCompletion succeeded() { return new EventCompletion(Status.SUCCEEDED); }
        public static EventCompletion failed() { return new EventCompletion(Status.FAILED); }
        public static EventCompletion partial() { return new EventCompletion("PARTIAL"); }
        public EventCompletion status(String value) { status = value; return this; }
        public EventCompletion purpose(String value) { purpose = value; return this; }
        public EventCompletion label(String value) { label = value; return this; }
        public EventCompletion returnedCount(Integer value) { returnedCount = value; return this; }
        public EventCompletion reportedTotalCount(Integer value) { reportedTotalCount = value; return this; }
        public EventCompletion tokens(int input, int output) { inputTokens = input; outputTokens = output; return this; }
        public EventCompletion output(Object value) { output = value; return this; }
        public EventCompletion input(Object value) { input = value; return this; }
        public EventCompletion usage(Object value) { usage = value; return this; }
        public EventCompletion error(Object value) { error = value; return this; }
        public EventCompletion metadata(Object value) { metadata = value; return this; }
        public EventCompletion build() { return this; }
    }
}
