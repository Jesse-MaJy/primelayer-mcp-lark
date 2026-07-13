package com.larkconnect.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekClientTest {
    @Test
    void requestsJsonOutputForPresentationFormatting() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = jsonServer(requestBody, """
                {"choices":[{"message":{"role":"assistant","content":"{\\"plainText\\":\\"结论\\",\\"markdown\\":\\"结论\\",\\"tables\\":[],\\"charts\\":[]}"}}],
                 "usage":{"prompt_tokens":7,"completion_tokens":3}}
                """);
        try {
            DeepSeekClient client = client(server);

            DeepSeekConversationClient.Completion result = client.formatPresentation(
                    "deepseek-v4-pro", List.of(Map.of("role", "assistant", "content", "原始回答")));

            assertThat(result.content()).contains("plainText");
            assertThat(result.inputTokens()).isEqualTo(7);
            assertThat(requestBody.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
            assertThat(requestBody.get()).contains("\"max_tokens\":12000");
            assertThat(requestBody.get()).doesNotContain("\"tools\"").doesNotContain("\"tool_choice\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsNativeToolsAndParsesMultipleToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                      {"id":"c1","type":"function","function":{"name":"mcp_query_tasks","arguments":"{\\\"projectIds\\\":[\\\"P1\\\"],\\\"arguments\\\":{}}"}},
                      {"id":"c2","type":"function","function":{"name":"mcp_get_report","arguments":"{\\\"projectIds\\\":[\\\"P1\\\"],\\\"arguments\\\":{}}"}}
                    ]}}],"usage":{"prompt_tokens":20,"completion_tokens":10}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:deepseek_client;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
            jdbc.execute("create table system_config (config_key varchar(128) primary key, config_value text, description varchar(512), is_sensitive tinyint)");
            AiRuntimeConfigService settings = new AiRuntimeConfigService(jdbc);
            AppProperties properties = new AppProperties(
                    new AppProperties.Admin(3600, "admin", "admin"), new AppProperties.Agent(20, 30000, 30000),
                    new AppProperties.Feishu("", "", "", "", false),
                    new AppProperties.DeepSeek("http://localhost:" + server.getAddress().getPort(), "secret"),
                    new AppProperties.Mcp("http://localhost/mcp", "X-API-Key"));
            DeepSeekClient client = new DeepSeekClient(properties, settings, new ObjectMapper(), RestClient.builder());

            DeepSeekConversationClient.Completion result = client.complete(
                    List.of(Map.of("role", "user", "content", "查询")),
                    List.of(Map.of("type", "function", "function", Map.of("name", "mcp_query_tasks"))), true);

            assertThat(result.toolCalls()).extracting(DeepSeekConversationClient.ToolCall::name)
                    .containsExactly("mcp_query_tasks", "mcp_get_report");
            assertThat(result.inputTokens()).isEqualTo(20);
            assertThat(requestBody.get()).contains("\"tool_choice\":\"auto\"").contains("deepseek-v4-pro");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer jsonServer(AtomicReference<String> requestBody, String responseJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private DeepSeekClient client(HttpServer server) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:deepseek_json;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop all objects");
        jdbc.execute("create table system_config (config_key varchar(128) primary key, config_value text, description varchar(512), is_sensitive tinyint)");
        AiRuntimeConfigService settings = new AiRuntimeConfigService(jdbc);
        AppProperties properties = new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"), new AppProperties.Agent(20, 30000, 30000),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("http://localhost:" + server.getAddress().getPort(), "secret"),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key"));
        return new DeepSeekClient(properties, settings, new ObjectMapper(), RestClient.builder());
    }
}
