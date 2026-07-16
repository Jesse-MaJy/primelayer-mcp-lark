package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.agent.StreamingStatistics;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class McpAdapter {
    private final AppProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public McpAdapter(AppProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.agent().mcpTimeoutMs());
        requests.setReadTimeout(properties.agent().mcpTimeoutMs());
        this.restClient = builder.requestFactory(requests).baseUrl(properties.mcp().endpoint()).build();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> listTools(String token) {
        return listToolsObserved(token).rawResponse();
    }

    public ObservedCall listToolsObserved(String token) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        );
        return executeObservedRequest(token, payload);
    }

    public Map<String, Object> callTool(String token, String toolName, Map<String, Object> arguments) {
        return callToolObserved(token, toolName, arguments).rawResponse();
    }

    public ObservedCall callToolObserved(String token, String toolName, Map<String, Object> arguments) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments)
        );
        return executeObservedRequest(token, payload);
    }

    protected ObservedCall executeObservedRequest(String token, Map<String, Object> payload) {
        long started = System.nanoTime();
        Map<String, Object> response = send(token, payload);
        long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return new ObservedCall(payload, response, latency,
                extractReturnedCount(response), extractReportedTotalCount(response));
    }

    private Map<String, Object> send(String token, Map<String, Object> payload) {
        McpSession session = tryInitialize(token);
        if (session.initialized()) {
            sendNotification(token, session.sessionId());
        }
        return sendRequest(token, payload, session.sessionId());
    }

    private McpSession tryInitialize(String token) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "lark-connect-agent-gateway", "version", "0.1.0")
                )
        );
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("")
                    .header(properties.mcp().authHeaderName(), token)
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
            parseResponse(response.getBody());
            return new McpSession(true, response.getHeaders().getFirst("Mcp-Session-Id"));
        } catch (Exception ignored) {
            return new McpSession(false, null);
        }
    }

    private void sendNotification(String token, String sessionId) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        );
        try {
            sendRequest(token, payload, sessionId);
        } catch (Exception ignored) {
            // Some servers do not return a body for notifications; the following request is the important one.
        }
    }

    private Map<String, Object> sendRequest(String token, Map<String, Object> payload, String sessionId) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri("")
                .header(properties.mcp().authHeaderName(), token)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (sessionId != null && !sessionId.isBlank()) {
            spec.header("Mcp-Session-Id", sessionId);
        }
        String body = spec.body(payload)
                .retrieve()
                .body(String.class);
        return parseResponse(body);
    }

    Map<String, Object> parseResponse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        String json = extractJson(body);
        Map<String, Object> response;
        try {
            response = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("MCP 返回内容不是可解析 JSON：" + body, e);
        }
        Object error = response.get("error");
        if (error instanceof Map<?, ?> errorMap) {
            Object code = errorMap.get("code");
            Object message = errorMap.get("message");
            throw new IllegalStateException("MCP JSON-RPC 错误"
                    + (code == null ? "" : " [" + code + "]")
                    + (message == null ? "" : "：" + message));
        }
        if (error != null) throw new IllegalStateException("MCP JSON-RPC 错误：" + error);
        Object result = response.get("result");
        if (result instanceof Map<?, ?> resultMap && Boolean.TRUE.equals(resultMap.get("isError"))) {
            Object content = resultMap.get("content");
            String detail;
            try {
                detail = objectMapper.writeValueAsString(content);
            } catch (Exception ignored) {
                detail = String.valueOf(content);
            }
            if (detail.length() > 1000) detail = detail.substring(0, 1000) + "...";
            throw new IllegalStateException("MCP 工具执行失败：" + detail);
        }
        return response;
    }

    private String extractJson(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        for (String line : trimmed.split("\\R")) {
            String current = line.trim();
            if (current.startsWith("data:")) {
                String data = current.substring("data:".length()).trim();
                if (data.startsWith("{")) {
                    return data;
                }
            }
        }
        return trimmed;
    }

    private record McpSession(boolean initialized, String sessionId) {}

    public record PaginationResult(
            List<PageData> pages,
            int totalCount,
            int totalPages,
            int successPages,
            int failedPages,
            boolean limitReached,
            int fetchedCount,
            Integer reportedTotalCount,
            boolean coverageComplete,
            String incompleteReason,
            List<Object> items,
            Map<String, Object> statistics
    ) {
        public PaginationResult(List<PageData> pages, int totalCount, int totalPages, int successPages,
                                int failedPages, boolean limitReached, int fetchedCount,
                                Integer reportedTotalCount, boolean coverageComplete,
                                String incompleteReason, List<?> items) {
            this(pages, totalCount, totalPages, successPages, failedPages, limitReached, fetchedCount,
                    reportedTotalCount, coverageComplete, incompleteReason,
                    items == null ? List.of() : new ArrayList<>(items), Map.of());
        }
    }

    public record PageData(
            int page,
            int pageSize,
            String status,
            Map<String, Object> rawRequest,
            Map<String, Object> rawResponse,
            String error,
            long latencyMs,
            Integer returnedCount,
            Integer reportedTotalCount,
            Integer cumulativeFetchedCount,
            Double coveragePercent,
            boolean duplicate
    ) {
        public PageData(int page, int pageSize, String status, Map<String, Object> rawRequest,
                        Map<String, Object> rawResponse, String error, long latencyMs) {
            this(page, pageSize, status, rawRequest, rawResponse, error, latencyMs,
                    null, null, null, null, false);
        }
        public PageData(int page, int pageSize, String status, Map<String, Object> rawRequest,
                        Map<String, Object> rawResponse, String error, long latencyMs,
                        Integer returnedCount, Integer reportedTotalCount) {
            this(page, pageSize, status, rawRequest, rawResponse, error, latencyMs,
                    returnedCount, reportedTotalCount, null, null, false);
        }
    }

    public record ObservedCall(Map<String, Object> rawRequest, Map<String, Object> rawResponse,
                               long latencyMs, Integer returnedCount, Integer reportedTotalCount) {}

    public enum PaginationStyle { AUTO, PAGE_SIZE, OFFSET_LIMIT, CURSOR }

    public record PaginationContinuation(int nextPage, String cursor, List<String> pageFingerprints,
                                         int successPages, int failedPages,
                                         Map<String, Object> statisticsState) {}

    public record PaginationBatchResult(PaginationResult result, PaginationContinuation continuation,
                                        boolean complete) {}

    public interface PaginationObserver {
        Object onStart(int pageIndex, int pageSize, Map<String, Object> rawRequest);
        void onComplete(Object context, PageData page);
    }

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final long PAGINATION_DEADLINE_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(30);

    public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments) {
        return callToolWithPagination(token, toolName, arguments, DEFAULT_PAGE_SIZE);
    }

    public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments, int pageSize) {
        return callToolWithPagination(token, toolName, arguments, pageSize, PaginationStyle.AUTO);
    }

    public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments,
                                                   int pageSize, PaginationStyle style) {
        return callToolWithPagination(token, toolName, arguments, pageSize, style, null);
    }

    public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments,
                                                   int pageSize, PaginationStyle style,
                                                   PaginationObserver observer) {
        return paginate(token, toolName, arguments, pageSize, style, observer,
                Integer.MAX_VALUE, null).result();
    }

    public PaginationBatchResult callToolWithPaginationBatch(
            String token, String toolName, Map<String, Object> arguments, int pageSize,
            PaginationStyle style, PaginationObserver observer, int maxPages,
            PaginationContinuation continuation) {
        return paginate(token, toolName, arguments, pageSize, style, observer,
                Math.max(1, maxPages), continuation);
    }

    private PaginationBatchResult paginate(String token, String toolName, Map<String, Object> arguments,
                                            int pageSize, PaginationStyle style, PaginationObserver observer,
                                            int maxPages, PaginationContinuation continuation) {
        List<PageData> pages = new ArrayList<>();
        int totalCount = 0;
        int successPages = continuation == null ? 0 : continuation.successPages();
        int failedPages = continuation == null ? 0 : continuation.failedPages();
        String incompleteReason = null;
        boolean exhausted = false;
        StreamingStatistics streamingStatistics = continuation == null
                ? new StreamingStatistics(objectMapper)
                : StreamingStatistics.restore(objectMapper, continuation.statisticsState());
        int fetchedCount = Math.toIntExact(streamingStatistics.fetchedCount());
        Integer reportedTotalCount = streamingStatistics.reportedTotalCount() == null ? null
                : Math.toIntExact(streamingStatistics.reportedTotalCount());

        boolean usePageStyle = style == PaginationStyle.PAGE_SIZE
                || style == PaginationStyle.AUTO && (arguments.containsKey("page") || arguments.containsKey("pageSize"));
        boolean useOffsetStyle = style == PaginationStyle.OFFSET_LIMIT
                || style == PaginationStyle.AUTO && (arguments.containsKey("offset") || arguments.containsKey("limit"));
        boolean useCursorStyle = style == PaginationStyle.CURSOR;
        if (!usePageStyle && !useOffsetStyle && !useCursorStyle) {
            usePageStyle = true;
        }

        String cursorKey = arguments.containsKey("pageToken") ? "pageToken"
                : arguments.containsKey("page_token") ? "page_token" : "cursor";
        String currentCursor = continuation != null ? continuation.cursor()
                : arguments.get(cursorKey) == null ? null : String.valueOf(arguments.get(cursorKey));
        java.util.Set<String> pageFingerprints = continuation == null
                ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(continuation.pageFingerprints());

        int page = continuation == null ? 0 : continuation.nextPage();
        int batchPages = 0;
        long deadline = System.nanoTime() + PAGINATION_DEADLINE_NANOS;

        while (System.nanoTime() < deadline && batchPages < maxPages) {
            Map<String, Object> pageArgs = new LinkedHashMap<>(arguments);

            if (useCursorStyle) {
                pageArgs.remove("cursor");
                pageArgs.remove("pageToken");
                pageArgs.remove("page_token");
                if (currentCursor != null && !currentCursor.isBlank()) pageArgs.put(cursorKey, currentCursor);
                pageArgs.putIfAbsent("pageSize", pageSize);
            } else if (usePageStyle) {
                pageArgs.put("page", page + 1);
                pageArgs.put("pageSize", pageSize);
            } else {
                pageArgs.put("offset", page * pageSize);
                pageArgs.put("limit", pageSize);
            }

            long started = System.currentTimeMillis();
            Map<String, Object> rawRequest = buildRequestPayload(toolName, pageArgs);
            Object observerContext = observer == null ? null : observer.onStart(page, pageSize, rawRequest);
            try {
                ObservedCall observed = executeObservedRequest(token, rawRequest);
                Map<String, Object> response = observed.rawResponse();
                rawRequest = observed.rawRequest();
                long latency = observed.latencyMs();
                NormalizedMcpPayload normalized = normalizePayload(response);
                List<Object> items = normalized.items();
                if (reportedTotalCount == null) reportedTotalCount = normalized.reportedTotalCount();
                if (reportedTotalCount != null) totalCount = reportedTotalCount;
                String fingerprint = pageFingerprint(items);
                boolean duplicatePage = !items.isEmpty() && !pageFingerprints.add(fingerprint);
                if (!duplicatePage) {
                    List<Map<String, Object>> records = new ArrayList<>();
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> record = new LinkedHashMap<>();
                            map.forEach((key, value) -> record.put(String.valueOf(key), value));
                            records.add(record);
                        }
                    }
                    streamingStatistics.accept(records, normalized.reportedTotalCount());
                    fetchedCount += items.size();
                }
                PageData observedPage = new PageData(page, pageSize, Status.SUCCEEDED, rawRequest, response, null, latency,
                        items.size(), normalized.reportedTotalCount(), fetchedCount,
                        reportedTotalCount == null ? null : reportedTotalCount == 0 ? 100.0
                                : Math.min(100.0, fetchedCount * 100.0 / reportedTotalCount),
                        duplicatePage);
                if (observer != null) observer.onComplete(observerContext, observedPage);
                PageData pageData = new PageData(page, pageSize, Status.SUCCEEDED, rawRequest,
                        new McpTraceSampler(objectMapper).sample(response), null, latency,
                        items.size(), normalized.reportedTotalCount(), fetchedCount,
                        observedPage.coveragePercent(), duplicatePage);
                pages.add(pageData);
                successPages++;

                if (duplicatePage) {
                    exhausted = true;
                    incompleteReason = "duplicate_page_before_total: fetched=" + fetchedCount
                            + ", reportedTotal=" + (reportedTotalCount == null ? "unknown" : reportedTotalCount);
                    break;
                }

                if (reportedTotalCount != null && fetchedCount >= reportedTotalCount) {
                    exhausted = true;
                    break;
                }
                if (items.isEmpty()) {
                    exhausted = true;
                    if (reportedTotalCount != null && fetchedCount < reportedTotalCount) {
                        incompleteReason = "empty_page_before_total: fetched=" + fetchedCount
                                + ", reportedTotal=" + reportedTotalCount;
                    }
                    break;
                }
                if (Boolean.FALSE.equals(normalized.hasMore())) {
                    exhausted = true;
                    if (reportedTotalCount != null && fetchedCount < reportedTotalCount) {
                        incompleteReason = "has_more_false_before_total: fetched=" + fetchedCount
                                + ", reportedTotal=" + reportedTotalCount;
                    }
                    break;
                }
                if (useCursorStyle && Boolean.TRUE.equals(normalized.hasMore())) {
                    if (normalized.nextCursor() == null || normalized.nextCursor().isBlank()) {
                        exhausted = true;
                        incompleteReason = "missing_next_cursor";
                        break;
                    }
                    currentCursor = normalized.nextCursor();
                }
                if (reportedTotalCount == null && !Boolean.TRUE.equals(normalized.hasMore())
                        && items.size() < pageSize) {
                    exhausted = true;
                    break;
                }
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - started;
                PageData pageData = new PageData(page, pageSize, Status.FAILED, rawRequest, null, readableError(e), latency,
                        null, null);
                pages.add(pageData);
                if (observer != null) observer.onComplete(observerContext, pageData);
                failedPages++;
                if (page == 0) {
                    PaginationResult failed = new PaginationResult(pages, 0, 1, successPages, failedPages, false,
                            0, null, false, "first_page_failed", List.of(), streamingStatistics.snapshot());
                    return new PaginationBatchResult(failed, null, true);
                }
                incompleteReason = "page_failed_before_completion";
                break;
            }

            page++;
            batchPages++;
        }

        int totalPages = successPages + failedPages;
        boolean limitReached = !exhausted && System.nanoTime() >= deadline;
        if (limitReached && (reportedTotalCount == null || fetchedCount < reportedTotalCount)) {
            incompleteReason = "pagination_deadline_reached";
        }
        boolean coverageComplete = incompleteReason == null && (reportedTotalCount == null
                ? exhausted : fetchedCount >= reportedTotalCount);
        if (totalCount == 0) totalCount = fetchedCount;
        Map<String, Object> statistics = streamingStatistics.snapshot();
        List<?> evidenceItems = statistics.get("evidenceSamples") instanceof List<?> list ? list : List.of();
        PaginationResult result = new PaginationResult(pages, totalCount, totalPages, successPages, failedPages, limitReached,
                fetchedCount, reportedTotalCount, coverageComplete, incompleteReason,
                List.copyOf(evidenceItems), statistics);
        boolean complete = exhausted || limitReached || incompleteReason != null;
        PaginationContinuation next = complete ? null : new PaginationContinuation(page, currentCursor,
                List.copyOf(pageFingerprints), successPages, failedPages, streamingStatistics.state());
        return new PaginationBatchResult(result, next, complete);
    }

    int extractTotalCount(Map<String, Object> response) {
        Integer reported = extractReportedTotalCount(response);
        if (reported != null) return reported;
        Integer returned = extractReturnedCount(response);
        return returned == null ? 0 : returned;
    }

    Integer extractReportedTotalCount(Map<String, Object> response) {
        Integer normalized = normalizePayload(response).reportedTotalCount();
        if (normalized != null) return normalized;
        Object result = response.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = result instanceof Map ? (Map<String, Object>) result : response;
        if (resultMap.containsKey("totalPages")) {
            int totalPages = toInt(resultMap.get("totalPages"));
            int resultPageSize = toInt(resultMap.getOrDefault("pageSize", DEFAULT_PAGE_SIZE));
            return totalPages * resultPageSize;
        }
        return null;
    }

    Integer extractReturnedCount(Map<String, Object> response) {
        List<?> items = extractItems(response);
        return items == null ? null : items.size();
    }

    List<?> extractItems(Map<String, Object> response) {
        List<Object> items = normalizePayload(response).items();
        return items.isEmpty() ? null : items;
    }

    NormalizedMcpPayload normalizePayload(Map<String, Object> response) {
        return NormalizedMcpPayload.from(objectMapper, response);
    }

    private String pageFingerprint(List<Object> items) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(items);
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ignored) {
            return Integer.toHexString(items.hashCode());
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private Map<String, Object> buildRequestPayload(String toolName, Map<String, Object> arguments) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments)
        );
    }

    private String readableError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "MCP 调用失败";
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
