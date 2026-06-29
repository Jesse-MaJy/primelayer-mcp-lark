package com.larkconnect.agent.mcp;

import com.larkconnect.agent.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
public class McpAdapter {
    private final AppProperties properties;
    private final RestClient restClient;

    public McpAdapter(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.mcp().endpoint()).build();
    }

    public Map<String, Object> callTool(String token, String toolName, Map<String, Object> arguments) {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments)
        );
        return restClient.post()
                .uri("")
                .header(properties.mcp().authHeaderName(), token)
                .header("Accept", "application/json, text/event-stream")
                .body(payload)
                .retrieve()
                .body(Map.class);
    }
}
