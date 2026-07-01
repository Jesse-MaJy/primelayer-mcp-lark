package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
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
}
