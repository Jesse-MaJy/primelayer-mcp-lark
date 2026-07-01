package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
        this.restClient = builder.baseUrl(properties.mcp().endpoint()).build();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> listTools(String token) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of()
        );
        return send(token, payload);
    }

    public Map<String, Object> callTool(String token, String toolName, Map<String, Object> arguments) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments)
        );
        return send(token, payload);
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

    private Map<String, Object> parseResponse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        String json = extractJson(body);
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("MCP 返回内容不是可解析 JSON：" + body, e);
        }
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
            int failedPages
    ) {}

    public record PageData(
            int page,
            int pageSize,
            String status,
            Map<String, Object> rawRequest,
            Map<String, Object> rawResponse,
            String error,
            long latencyMs
    ) {}

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;

    public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments) {
        return callToolWithPagination(token, toolName, arguments, DEFAULT_PAGE_SIZE);
    }

    public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments, int pageSize) {
        List<PageData> pages = new ArrayList<>();
        int totalCount = 0;
        int successPages = 0;
        int failedPages = 0;

        Map<String, Object> pageArgs = new LinkedHashMap<>(arguments);
        pageArgs.put("offset", 0);
        pageArgs.put("limit", pageSize);

        long started = System.currentTimeMillis();
        Map<String, Object> rawRequest = buildRequestPayload(toolName, pageArgs);
        try {
            Map<String, Object> response = callTool(token, toolName, pageArgs);
            long latency = System.currentTimeMillis() - started;
            totalCount = extractTotalCount(response);
            pages.add(new PageData(0, pageSize, "SUCCEEDED", rawRequest, response, null, latency));
            successPages++;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - started;
            pages.add(new PageData(0, pageSize, "FAILED", rawRequest, null, readableError(e), latency));
            failedPages++;
            return new PaginationResult(pages, 0, 1, successPages, failedPages);
        }

        if (totalCount <= pageSize) {
            return new PaginationResult(pages, totalCount, 1, successPages, failedPages);
        }

        int totalPages = Math.min((int) Math.ceil((double) totalCount / pageSize), MAX_PAGES);

        for (int page = 1; page < totalPages; page++) {
            int offset = page * pageSize;
            Map<String, Object> args = new LinkedHashMap<>(arguments);
            args.put("offset", offset);
            args.put("limit", pageSize);

            started = System.currentTimeMillis();
            Map<String, Object> req = buildRequestPayload(toolName, args);
            try {
                Map<String, Object> resp = callTool(token, toolName, args);
                long latency = System.currentTimeMillis() - started;
                pages.add(new PageData(page, pageSize, "SUCCEEDED", req, resp, null, latency));
                successPages++;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - started;
                pages.add(new PageData(page, pageSize, "FAILED", req, null, readableError(e), latency));
                failedPages++;
            }
        }

        return new PaginationResult(pages, totalCount, totalPages, successPages, failedPages);
    }

    private int extractTotalCount(Map<String, Object> response) {
        Object result = response.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = result instanceof Map ? (Map<String, Object>) result : response;
        if (resultMap.containsKey("totalCount")) {
            return toInt(resultMap.get("totalCount"));
        }
        if (resultMap.containsKey("total")) {
            return toInt(resultMap.get("total"));
        }
        if (resultMap.containsKey("count")) {
            return toInt(resultMap.get("count"));
        }
        Object items = resultMap.get("items");
        if (items instanceof List<?> list) {
            return list.size();
        }
        Object data = resultMap.get("data");
        if (data instanceof List<?> list) {
            return list.size();
        }
        return 0;
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
