package com.larkconnect.agent.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.agent.UnifiedQueryService;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekClient;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.feishu.FeishuEventParser;
import com.larkconnect.agent.feishu.FeishuIncomingMessage;
import com.larkconnect.agent.mcp.McpAdapter;
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
    private final JdbcTemplate jdbc;
    private final ConnectionFactory rabbit;
    private final AppProperties properties;
    private final DeepSeekClient deepSeek;
    private final AiRuntimeConfigService aiSettings;
    private final UnifiedQueryService unifiedQuery;
    private final McpAdapter mcp;
    private final TokenCryptoService crypto;
    private final FeishuClient feishu;
    private final FeishuEventParser parser;
    private final AgentTaskService tasks;
    private final ObjectMapper objectMapper;
    private final FeishuDemoCardCatalog demoCardCatalog;

    public DebugService(JdbcTemplate jdbc, ConnectionFactory rabbit, AppProperties properties,
                        DeepSeekClient deepSeek, AiRuntimeConfigService aiSettings,
                        UnifiedQueryService unifiedQuery, McpAdapter mcp, TokenCryptoService crypto,
                        FeishuClient feishu, FeishuEventParser parser, AgentTaskService tasks,
                        ObjectMapper objectMapper, FeishuDemoCardCatalog demoCardCatalog) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.properties = properties;
        this.deepSeek = deepSeek;
        this.aiSettings = aiSettings;
        this.unifiedQuery = unifiedQuery;
        this.mcp = mcp;
        this.crypto = crypto;
        this.feishu = feishu;
        this.parser = parser;
        this.tasks = tasks;
        this.objectMapper = objectMapper;
        this.demoCardCatalog = demoCardCatalog;
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mysql", checkMysql());
        result.put("rabbitmq", checkRabbit());
        result.put("deepSeekApiKeyConfigured", hasText(properties.deepseek().apiKey()));
        result.put("deepSeekBaseUrl", properties.deepseek().baseUrl());
        result.put("deepSeekModel", aiSettings.currentModel());
        result.put("mcpEndpointConfigured", hasText(properties.mcp().endpoint()));
        result.put("mcpEndpoint", properties.mcp().endpoint());
        result.put("feishuAppIdConfigured", hasText(properties.feishu().appId()));
        result.put("feishuAppSecretConfigured", hasText(properties.feishu().appSecret()));
        result.put("feishuVerificationTokenConfigured", hasText(properties.feishu().verificationToken()));
        result.put("feishuEchoEnabled", properties.feishu().echoEnabled());
        return result;
    }

    public Map<String, Object> testDeepSeekConnection(DebugDtos.DeepSeekConnectionRequest request) {
        return deepSeek.testConnection(request == null ? null : request.prompt());
    }

    public Map<String, Object> callMcp(DebugDtos.McpCallRequest request) {
        TokenRecord token = loadToken(request.tokenId());
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        try {
            List<Map<String, Object>> tools = extractTools(mcp.listTools(token.token()));
            if (tools.stream().noneMatch(tool -> request.toolName().equals(String.valueOf(tool.get("name"))))) {
                throw new IllegalArgumentException("MCP 服务未暴露工具：" + request.toolName());
            }
            return Map.of("ok", true, "token", sanitize(token), "toolName", request.toolName(),
                    "arguments", arguments, "result", mcp.callTool(token.token(), request.toolName(), arguments));
        } catch (Exception e) {
            return Map.of("ok", false, "token", sanitize(token), "toolName", request.toolName(),
                    "arguments", arguments, "error", sanitizeError(e.getMessage(), token.token()));
        }
    }

    public Map<String, Object> listMcpTools(DebugDtos.McpToolsRequest request) {
        TokenRecord token = loadToken(request.tokenId());
        try {
            Map<String, Object> raw = mcp.listTools(token.token());
            List<Map<String, Object>> tools = extractTools(raw);
            return Map.of("ok", true, "token", sanitize(token), "toolCount", tools.size(), "tools", tools, "raw", raw);
        } catch (Exception e) {
            return Map.of("ok", false, "token", sanitize(token), "error", sanitizeError(e.getMessage(), token.token()));
        }
    }

    public Map<String, Object> queryAgent(DebugDtos.AgentQueryRequest request) {
        String requestId = UUID.randomUUID().toString();
        String chatType = hasText(request.chatType()) ? request.chatType() : "p2p";
        String chatId = hasText(request.feishuChatId()) ? request.feishuChatId() : "debug-chat";
        UnifiedQueryService.QueryResult result = unifiedQuery.query(new UnifiedQueryService.QueryRequest(
                requestId, request.message(), chatType, request.feishuOpenId(), chatId));
        if (Boolean.TRUE.equals(request.sendFeishuMessage()) && hasText(request.feishuChatId())) {
            feishu.sendAnswerCard(request.feishuChatId(), request.message(), result.presentation(),
                    "mcp_deepseek".equals(result.path()) ? "项目数据分析" : "DeepSeek 回答", "blue");
        }
        Map<String, Object> response = objectMapper.convertValue(result, new TypeReference<LinkedHashMap<String, Object>>() {});
        response.put("requestId", requestId);
        response.put("finalAnswer", result.answer());
        return response;
    }

    public Map<String, Object> mockFeishuEvent(DebugDtos.FeishuMockEventRequest request) {
        FeishuIncomingMessage message = parser.parse(request.event());
        if (message == null) return Map.of("accepted", false, "parsed", false, "reason", "事件不是可处理的飞书文本消息");
        tasks.createAndPublish(message);
        return Map.of("accepted", true, "parsed", true, "message", Map.of(
                "messageId", message.messageId(), "openId", message.openId(), "chatId", message.chatId(),
                "chatType", message.chatType(), "text", message.text()));
    }

    public Map<String, Object> checkFeishuToken() { return feishu.checkTenantAccessToken(); }

    public List<FeishuDemoCardCatalog.CardPreset> feishuCardPresets() {
        return demoCardCatalog.presets();
    }

    public Map<String, Object> testFeishuReply(DebugDtos.FeishuReplyTestRequest request) {
        try {
            String text = hasText(request.text()) ? request.text() : "飞书回复接口测试";
            feishu.replyText(request.messageId(), text);
            return Map.of("ok", true, "messageId", request.messageId(), "text", text);
        } catch (Exception e) {
            return Map.of("ok", false, "messageId", request.messageId(), "error", readable(e));
        }
    }

    public Map<String, Object> sendFeishuCard(DebugDtos.FeishuCardSendRequest request) {
        validateReceiveIdType(request.receiveIdType());
        try {
            return Map.of("ok", true, "receiveIdType", request.receiveIdType(), "receiveId", request.receiveId(),
                    "response", feishu.sendCard(request.receiveIdType(), request.receiveId(), request.card()));
        } catch (Exception e) {
            return Map.of("ok", false, "receiveIdType", request.receiveIdType(), "receiveId", request.receiveId(), "error", readable(e));
        }
    }

    public Map<String, Object> sendFeishuCardBatch(DebugDtos.FeishuCardBatchSendRequest request) {
        validateReceiveIdType(request.receiveIdType());
        List<String> ids = request.receiveIds().stream().filter(this::hasText).map(String::trim).distinct().toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("请选择至少一个接收人");
        List<Map<String, Object>> items = new ArrayList<>();
        int success = 0;
        for (String id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("receiveId", id);
            try { item.put("response", feishu.sendCard(request.receiveIdType(), id, request.card())); item.put("ok", true); success++; }
            catch (Exception e) { item.put("ok", false); item.put("error", readable(e)); }
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", success == ids.size());
        response.put("receiveIdType", request.receiveIdType());
        response.put("total", ids.size());
        response.put("succeeded", success);
        response.put("failed", ids.size() - success);
        response.put("results", items);
        return response;
    }

    private TokenRecord loadToken(Long id) {
        List<TokenRecord> rows = jdbc.query("""
                select id, project_id, project_name, project_remark, mcp_token_ciphertext
                from project_mcp_token where id = ?
                """, (rs, rowNum) -> new TokenRecord(rs.getLong("id"), rs.getString("project_id"),
                rs.getString("project_name"), rs.getString("project_remark"),
                crypto.decrypt(rs.getString("mcp_token_ciphertext"))), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到有效 MCP token 记录：" + id);
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }

    private Map<String, Object> sanitize(TokenRecord token) {
        return Map.of("id", token.id(), "projectId", safe(token.projectId()), "projectName", safe(token.projectName()),
                "projectRemark", safe(token.projectRemark()));
    }

    private Map<String, Object> checkMysql() {
        try { return Map.of("ok", true, "value", jdbc.queryForObject("select 1", Integer.class)); }
        catch (Exception e) { return Map.of("ok", false, "error", readable(e)); }
    }

    private Map<String, Object> checkRabbit() {
        try (Connection connection = rabbit.createConnection()) { return Map.of("ok", connection.isOpen()); }
        catch (Exception e) { return Map.of("ok", false, "error", readable(e)); }
    }

    private void validateReceiveIdType(String type) {
        if (!List.of("open_id", "user_id", "union_id", "email", "chat_id").contains(type)) {
            throw new IllegalArgumentException("不支持的 receive_id_type：" + type);
        }
    }

    private String sanitizeError(String message, String secret) {
        String safe = message == null ? "请求失败" : message;
        return hasText(secret) ? safe.replace(secret, "***REDACTED***") : safe;
    }
    private String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }

    private record TokenRecord(Long id, String projectId, String projectName, String projectRemark, String token) {}
}
