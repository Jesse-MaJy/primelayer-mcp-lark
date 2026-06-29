package com.larkconnect.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekClient;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
import com.larkconnect.agent.feishu.FeishuEventParser;
import com.larkconnect.agent.feishu.FeishuIncomingMessage;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.mcp.McpToolRegistry;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DebugService {
    private final JdbcTemplate jdbcTemplate;
    private final ConnectionFactory rabbitConnectionFactory;
    private final AppProperties properties;
    private final DeepSeekClient deepSeekClient;
    private final TokenResolver tokenResolver;
    private final McpToolRegistry toolRegistry;
    private final McpAdapter mcpAdapter;
    private final TokenCryptoService cryptoService;
    private final FeishuEventParser feishuEventParser;
    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;

    public DebugService(
            JdbcTemplate jdbcTemplate,
            ConnectionFactory rabbitConnectionFactory,
            AppProperties properties,
            DeepSeekClient deepSeekClient,
            TokenResolver tokenResolver,
            McpToolRegistry toolRegistry,
            McpAdapter mcpAdapter,
            TokenCryptoService cryptoService,
            FeishuEventParser feishuEventParser,
            AgentTaskService agentTaskService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitConnectionFactory = rabbitConnectionFactory;
        this.properties = properties;
        this.deepSeekClient = deepSeekClient;
        this.tokenResolver = tokenResolver;
        this.toolRegistry = toolRegistry;
        this.mcpAdapter = mcpAdapter;
        this.cryptoService = cryptoService;
        this.feishuEventParser = feishuEventParser;
        this.agentTaskService = agentTaskService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("mysql", checkMysql());
        health.put("rabbitmq", checkRabbit());
        health.put("deepSeekApiKeyConfigured", hasText(properties.deepseek().apiKey()));
        health.put("deepSeekBaseUrl", properties.deepseek().baseUrl());
        health.put("deepSeekModel", properties.deepseek().model());
        health.put("mcpEndpointConfigured", hasText(properties.mcp().endpoint()));
        health.put("mcpEndpoint", properties.mcp().endpoint());
        health.put("mcpAuthHeaderName", properties.mcp().authHeaderName());
        health.put("feishuAppIdConfigured", hasText(properties.feishu().appId()));
        health.put("feishuAppSecretConfigured", hasText(properties.feishu().appSecret()));
        health.put("feishuVerificationTokenConfigured", hasText(properties.feishu().verificationToken()));
        return health;
    }

    public DeepSeekPlan plan(DebugDtos.DeepSeekPlanRequest request) {
        return deepSeekClient.plan(UUID.randomUUID().toString(), request.question(), defaultChatType(request.chatType()));
    }

    public Map<String, Object> summarize(DebugDtos.DeepSeekSummarizeRequest request) {
        List<Map<String, Object>> toolResults = request.toolResults() == null ? List.of() : request.toolResults();
        String answer = deepSeekClient.summarize(request.question(), toolResults);
        return Map.of("answer", answer);
    }

    public Map<String, Object> callMcp(DebugDtos.McpCallRequest request) {
        TokenRecord token = loadToken(request.tokenId());
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : new LinkedHashMap<>(request.arguments());
        toolRegistry.validate(request.toolName(), arguments);
        Map<String, Object> result = mcpAdapter.callTool(token.token(), request.toolName(), arguments);
        return Map.of(
                "token", sanitizeToken(token),
                "toolName", request.toolName(),
                "arguments", arguments,
                "result", result
        );
    }

    public Map<String, Object> mockFeishuEvent(DebugDtos.FeishuMockEventRequest request) {
        FeishuIncomingMessage message = feishuEventParser.parse(request.event());
        if (message == null) {
            return Map.of("accepted", false, "parsed", false, "reason", "事件不是可处理的飞书文本消息");
        }
        agentTaskService.createAndPublish(message);
        return Map.of(
                "accepted", true,
                "parsed", true,
                "message", Map.of(
                        "messageId", message.messageId(),
                        "openId", message.openId(),
                        "chatId", message.chatId(),
                        "chatType", message.chatType(),
                        "text", message.text()
                )
        );
    }

    public Map<String, Object> queryAgent(DebugDtos.AgentQueryRequest request) {
        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String chatType = defaultChatType(request.chatType());
        String chatId = hasText(request.feishuChatId()) ? request.feishuChatId() : "debug-chat";
        DeepSeekPlan plan = deepSeekClient.plan(requestId, request.message(), chatType);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("plan", plan);
        if (plan.needClarification()) {
            response.put("finalAnswer", plan.clarificationQuestion());
            response.put("latencyMs", System.currentTimeMillis() - started);
            return response;
        }

        TokenResolver.ResolvedContext context = tokenResolver.resolve(
                request.feishuOpenId(),
                chatId,
                chatType,
                plan,
                properties.agent().maxProjectsPerQuery()
        );
        if (context.hasError()) {
            response.put("error", context.errorMessage());
            response.put("finalAnswer", context.errorMessage());
            response.put("latencyMs", System.currentTimeMillis() - started);
            return response;
        }

        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (TokenResolver.TokenEntry token : context.tokens()) {
            for (DeepSeekPlan.ToolCall toolCall : plan.toolCalls()) {
                Map<String, Object> toolResult = callToolSafely(context.primelayerUserId(), token, toolCall);
                toolResults.add(toolResult);
            }
        }

        String answer = deepSeekClient.summarize(request.message(), toolResults);
        response.put("resolved", Map.of(
                "primelayerUserId", context.primelayerUserId(),
                "projects", context.tokens().stream().map(this::sanitizeTokenEntry).toList()
        ));
        response.put("toolResults", toolResults);
        response.put("finalAnswer", answer);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> callToolSafely(String primelayerUserId, TokenResolver.TokenEntry token, DeepSeekPlan.ToolCall toolCall) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", token.projectId());
        result.put("projectName", token.projectName());
        result.put("toolName", toolCall.toolName());
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(toolCall.arguments());
            arguments.put("project_id", token.projectId());
            arguments.put("primelayer_user_id", primelayerUserId);
            toolRegistry.validate(toolCall.toolName(), arguments);
            result.put("status", Status.SUCCEEDED);
            result.put("arguments", arguments);
            result.put("result", mcpAdapter.callTool(token.token(), toolCall.toolName(), arguments));
        } catch (Exception e) {
            result.put("status", Status.FAILED);
            result.put("error", e.getMessage());
        }
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    private TokenRecord loadToken(Long tokenId) {
        List<TokenRecord> records = jdbcTemplate.query("""
                select id, primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_hash_suffix
                from project_mcp_token
                where id = ? and token_status = ?
                """, (rs, rowNum) -> new TokenRecord(
                rs.getLong("id"),
                rs.getString("primelayer_user_id"),
                rs.getString("project_id"),
                rs.getString("project_name"),
                cryptoService.decrypt(rs.getString("mcp_token_ciphertext")),
                rs.getString("token_hash_suffix")
        ), tokenId, Status.ACTIVE);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("未找到有效 MCP token 记录：" + tokenId);
        }
        return records.get(0);
    }

    private Map<String, Object> sanitizeToken(TokenRecord token) {
        return Map.of(
                "id", token.id(),
                "primelayerUserId", token.primelayerUserId(),
                "projectId", token.projectId(),
                "projectName", token.projectName(),
                "tokenHashSuffix", token.tokenHashSuffix()
        );
    }

    private Map<String, Object> sanitizeTokenEntry(TokenResolver.TokenEntry token) {
        return Map.of(
                "tokenId", token.tokenId(),
                "projectId", token.projectId(),
                "projectName", token.projectName()
        );
    }

    private Map<String, Object> checkMysql() {
        try {
            Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
            return Map.of("ok", value != null && value == 1);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkRabbit() {
        try (Connection connection = rabbitConnectionFactory.createConnection()) {
            return Map.of("ok", connection.isOpen());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private String defaultChatType(String chatType) {
        return hasText(chatType) ? chatType : "p2p";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @SuppressWarnings("unused")
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private record TokenRecord(
            Long id,
            String primelayerUserId,
            String projectId,
            String projectName,
            String token,
            String tokenHashSuffix
    ) {}
}
