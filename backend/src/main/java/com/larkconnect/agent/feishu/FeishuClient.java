package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class FeishuClient {
    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);
    private static final String DEFAULT_AI_TITLE = "Primelayer AI 回答";
    private static final String CARD_SCHEMA_VERSION = "primelayer-ai-card/v1";
    private static final DateTimeFormatter CARD_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile TenantToken tenantToken;

    public FeishuClient(AppProperties properties, ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl("https://open.feishu.cn").build();
    }

    public void sendText(String chatId, String text) {
        if (!isConfigured()) {
            log.info("Feishu app is not configured. Would send to {}: {}", chatId, text);
            return;
        }
        String content = toJson(Map.of("text", text));
        Map<String, Object> response = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/open-apis/im/v1/messages")
                        .queryParam("receive_id_type", "chat_id")
                        .build())
                .header("Authorization", "Bearer " + tenantAccessToken())
                .body(Map.of(
                        "receive_id", chatId,
                        "msg_type", "text",
                        "content", content
                ))
                .retrieve()
                .body(Map.class);
        assertFeishuOk(response, "send message");
    }

    public void sendAnswerCard(String chatId, String question, String answer) {
        sendAnswerCard(chatId, question, answer, DEFAULT_AI_TITLE, "blue");
    }

    public void sendAnswerCard(String chatId, String question, String answer, String title, String template) {
        sendAnswerCard(chatId, question, answer, title, template, List.of(), List.of());
    }

    public void sendAnswerCard(
            String chatId,
            String question,
            String answer,
            String title,
            String template,
            List<CardMetric> metrics,
            List<Map<String, Object>> visualElements
    ) {
        sendCard("chat_id", chatId, buildAnswerCard(question, answer, title, template, metrics, visualElements));
    }

    public void replyText(String messageId, String text) {
        if (!isConfigured()) {
            log.info("Feishu app is not configured. Would reply to {}: {}", messageId, text);
            return;
        }
        String content = toJson(Map.of("text", text));
        Map<String, Object> response = restClient.post()
                .uri("/open-apis/im/v1/messages/{messageId}/reply", messageId)
                .header("Authorization", "Bearer " + tenantAccessToken())
                .body(Map.of(
                        "msg_type", "text",
                        "content", content
                ))
                .retrieve()
                .body(Map.class);
        assertFeishuOk(response, "reply message");
    }

    public void replyAnswerCard(String messageId, String question, String answer) {
        replyAnswerCard(messageId, question, answer, DEFAULT_AI_TITLE, "blue");
    }

    public void replyAnswerCard(String messageId, String question, String answer, String title, String template) {
        replyAnswerCard(messageId, question, answer, title, template, List.of(), List.of());
    }

    public void replyAnswerCard(
            String messageId,
            String question,
            String answer,
            String title,
            String template,
            List<CardMetric> metrics,
            List<Map<String, Object>> visualElements
    ) {
        replyCard(messageId, buildAnswerCard(question, answer, title, template, metrics, visualElements));
    }

    public void replyWelcomeCard(String messageId) {
        replyCard(messageId, buildWelcomeCard());
    }

    public void replyMcpRequiredCard(String messageId, TokenResolver.McpConfigCheckResult result) {
        replyCard(messageId, buildMcpRequiredCard(result));
    }

    public void sendMcpRequiredCard(String receiveIdType, String receiveId, TokenResolver.McpConfigCheckResult result) {
        sendCard(receiveIdType, receiveId, buildMcpRequiredCard(result));
    }

    public void sendProjectEntryCard(String receiveIdType, String receiveId, TokenResolver.McpConfigCheckResult result) {
        sendCard(receiveIdType, receiveId, buildProjectEntryCard(result));
    }

    public void sendQuestionPromptCard(String receiveIdType, String receiveId, String question) {
        sendCard(receiveIdType, receiveId, buildQuestionPromptCard(question));
    }

    public Map<String, Object> replyCard(String messageId, Map<String, Object> card) {
        if (!isConfigured()) {
            return Map.of("ok", false, "error", "Feishu App ID 或 App Secret 未配置");
        }
        String content = toJson(card);
        Map<String, Object> response = restClient.post()
                .uri("/open-apis/im/v1/messages/{messageId}/reply", messageId)
                .header("Authorization", "Bearer " + tenantAccessToken())
                .body(Map.of(
                        "msg_type", "interactive",
                        "content", content
                ))
                .retrieve()
                .body(Map.class);
        assertFeishuOk(response, "reply card message");
        return response == null ? Map.of() : response;
    }

    public Map<String, Object> sendCard(String receiveIdType, String receiveId, Map<String, Object> card) {
        if (!isConfigured()) {
            return Map.of("ok", false, "error", "Feishu App ID 或 App Secret 未配置");
        }
        String content = toJson(card);
        Map<String, Object> response = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/open-apis/im/v1/messages")
                        .queryParam("receive_id_type", receiveIdType)
                        .build())
                .header("Authorization", "Bearer " + tenantAccessToken())
                .body(Map.of(
                        "receive_id", receiveId,
                        "msg_type", "interactive",
                        "content", content
                ))
                .retrieve()
                .body(Map.class);
        assertFeishuOk(response, "send card message");
        return response == null ? Map.of() : response;
    }

    private Map<String, Object> buildAnswerCard(
            String question,
            String answer,
            String title,
            String template,
            List<CardMetric> metrics,
            List<Map<String, Object>> visualElements
    ) {
        String generatedAt = LocalDateTime.now().format(CARD_TIME_FORMATTER);
        String cardTitle = hasText(title) ? title : DEFAULT_AI_TITLE;
        List<Object> elements = new ArrayList<>();
        elements.add(summaryFields(cardTitle, generatedAt, metrics));
        elements.add(Map.of("tag", "hr"));
        elements.add(markdownBlock("**问题**\n" + safeText(question)));
        elements.add(Map.of("tag", "hr"));
        elements.add(markdownBlock("**回答**\n" + safeText(answer)));
        if (visualElements != null && !visualElements.isEmpty()) {
            elements.add(Map.of("tag", "hr"));
            elements.addAll(visualElements);
        }
        elements.add(Map.of("tag", "hr"));
        elements.add(noteBlock("由 Primelayer AI 生成 · " + generatedAt + " · " + CARD_SCHEMA_VERSION));
        return Map.of(
                "config", Map.of(
                        "wide_screen_mode", true,
                        "enable_forward", true
                ),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", cardTitle),
                        "template", hasText(template) ? template : "blue"
                ),
                "elements", elements
        );
    }

    private Map<String, Object> buildQuestionPromptCard(String question) {
        return Map.of(
                "config", Map.of("wide_screen_mode", true, "enable_forward", true),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", "Primelayer AI 预设问题"),
                        "template", "blue"
                ),
                "elements", List.of(
                        markdownBlock("**请复制或直接发送下面的问题：**\n" + safeText(question)),
                        noteBlock("当前版本先提供问题入口；后续可升级为点击按钮后自动发起 Agent 查询。")
                )
        );
    }

    private Map<String, Object> buildWelcomeCard() {
        return Map.of(
                "config", Map.of("wide_screen_mode", true, "enable_forward", true),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", "Primelayer AI"),
                        "template", "blue"
                ),
                "elements", List.of(
                        markdownBlock("**欢迎使用 Primelayer AI**\n点击下方按钮检查你的 MCP 配置。配置正常后，我会给你推送项目入口卡。"),
                        actionBlock(List.of(buttonAction("开始使用 / 检查配置", "check_mcp_config", "primary", Map.of("source", "primelayer_ai_welcome")))),
                        noteBlock("不会在飞书内收集 MCP Token；Token 仍由管理员在后台维护。")
                )
        );
    }

    private Map<String, Object> buildMcpRequiredCard(TokenResolver.McpConfigCheckResult result) {
        String reason = result == null || !hasText(result.reason()) ? "当前账号缺少 MCP 配置。" : result.reason();
        String openId = result == null ? "-" : result.openId();
        String ownerType = result == null || !hasText(result.ownerType()) ? "OPEN_ID" : result.ownerType();
        String ownerId = result == null || !hasText(result.ownerId()) ? openId : result.ownerId();
        return Map.of(
                "config", Map.of("wide_screen_mode", true, "enable_forward", true),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", "需要绑定 MCP 配置"),
                        "template", "red"
                ),
                "elements", List.of(
                        markdownBlock("**当前还不能查询项目数据**\n" + safeText(reason)),
                        fieldBlock(List.of(
                                shortField("**飞书 open_id**\n" + safeText(openId)),
                                shortField("**Token 绑定对象**\n" + safeText(ownerType) + " / " + safeText(ownerId))
                        )),
                        Map.of("tag", "hr"),
                        markdownBlock("**管理员处理步骤**\n1. 打开后台「人员配置」。\n2. 选择当前飞书 open_id 或群 chat_id 作为绑定对象。\n3. 粘贴并验证 MCP Token，填写项目备注名。\n4. 配置完成后回到飞书点击「检查配置」。"),
                        noteBlock("出于安全考虑，请不要在飞书消息中发送 MCP Token 明文。")
                )
        );
    }

    private Map<String, Object> buildProjectEntryCard(TokenResolver.McpConfigCheckResult result) {
        List<TokenResolver.ProjectRef> projects = result == null || result.projects() == null ? List.of() : result.projects();
        String projectText = projects.isEmpty()
                ? "-"
                : projects.stream()
                        .limit(8)
                        .map(project -> "- " + safeText(project.projectName()) + " (`" + safeText(project.projectId()) + "`)")
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("-");
        List<Object> elements = new ArrayList<>();
        elements.add(markdownBlock("**配置已就绪**\n你可以开始查询 Primelayer 项目数据。"));
        elements.add(fieldBlock(List.of(
                shortField("**Token 绑定对象**\n" + safeText(result == null ? "-" : result.ownerType()) + " / " + safeText(result == null ? "-" : result.ownerId())),
                shortField("**可访问项目数**\n" + projects.size())
        )));
        elements.add(Map.of("tag", "hr"));
        elements.add(markdownBlock("**可查询项目**\n" + projectText));
        elements.add(actionBlock(List.of(
                buttonAction("今日项目施工情况", "preset_question", "primary", Map.of("question", "今天项目施工情况")),
                buttonAction("项目风险", "preset_question", "default", Map.of("question", "项目风险")),
                buttonAction("逾期任务", "preset_question", "default", Map.of("question", "逾期任务")),
                buttonAction("生成周报", "preset_question", "default", Map.of("question", "生成周报"))
        )));
        elements.add(noteBlock("点击预设问题后，可直接复制问题到聊天框提问；后续可继续接入按钮回调自动发起查询。"));
        return Map.of(
                "config", Map.of("wide_screen_mode", true, "enable_forward", true),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", "Primelayer AI 项目入口"),
                        "template", "green"
                ),
                "elements", elements
        );
    }

    private Map<String, Object> summaryFields(String title, String generatedAt, List<CardMetric> metrics) {
        List<Object> fields = new ArrayList<>();
        fields.add(shortField("**来源**\nPrimelayer AI"));
        fields.add(shortField("**类型**\n" + safeText(title)));
        fields.add(shortField("**卡片版本**\n" + CARD_SCHEMA_VERSION));
        fields.add(shortField("**生成时间**\n" + generatedAt));
        if (metrics != null) {
            metrics.stream()
                    .filter(metric -> metric != null && hasText(metric.label()))
                    .limit(4)
                    .forEach(metric -> fields.add(shortField("**" + safeText(metric.label()) + "**\n" + safeText(metric.value()))));
        }
        return Map.of(
                "tag", "div",
                "fields", fields
        );
    }

    private Map<String, Object> markdownBlock(String content) {
        return Map.of(
                "tag", "div",
                "text", Map.of(
                        "tag", "lark_md",
                        "content", content
                )
        );
    }

    private Map<String, Object> fieldBlock(List<Object> fields) {
        return Map.of(
                "tag", "div",
                "fields", fields
        );
    }

    private Map<String, Object> actionBlock(List<Object> actions) {
        return Map.of(
                "tag", "action",
                "actions", actions
        );
    }

    private Map<String, Object> buttonAction(String label, String action, String type, Map<String, Object> extraValue) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("action", action);
        if (extraValue != null) {
            value.putAll(extraValue);
        }
        return Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", label),
                "type", type,
                "value", value
        );
    }

    private Map<String, Object> shortField(String content) {
        return Map.of(
                "is_short", true,
                "text", Map.of(
                        "tag", "lark_md",
                        "content", content
                )
        );
    }

    private Map<String, Object> noteBlock(String content) {
        return Map.of(
                "tag", "note",
                "elements", List.of(Map.of(
                        "tag", "plain_text",
                        "content", content
                ))
        );
    }

    private String safeText(String value) {
        if (!hasText(value)) {
            return "-";
        }
        return value.length() <= 3000 ? value : value.substring(0, 3000) + "\n\n内容较长，已截断。";
    }

    public Map<String, Object> checkTenantAccessToken() {
        if (!isConfigured()) {
            return Map.of("ok", false, "error", "Feishu App ID 或 App Secret 未配置");
        }
        try {
            String token = tenantAccessToken();
            return Map.of(
                    "ok", true,
                    "tokenConfigured", token != null && !token.isBlank(),
                    "tokenPrefix", token == null || token.length() < 8 ? "***" : token.substring(0, 8) + "***"
            );
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private String tenantAccessToken() {
        TenantToken current = tenantToken;
        long now = System.currentTimeMillis();
        if (current != null && current.expiresAtMillis() - 60_000 > now) {
            return current.token();
        }
        synchronized (this) {
            current = tenantToken;
            now = System.currentTimeMillis();
            if (current != null && current.expiresAtMillis() - 60_000 > now) {
                return current.token();
            }
            Map<String, Object> response = restClient.post()
                    .uri("/open-apis/auth/v3/tenant_access_token/internal")
                    .body(Map.of(
                            "app_id", properties.feishu().appId(),
                            "app_secret", properties.feishu().appSecret()
                    ))
                    .retrieve()
                    .body(Map.class);
            assertFeishuOk(response, "get tenant_access_token");
            JsonNode root = objectMapper.valueToTree(response);
            String token = root.path("tenant_access_token").asText();
            long expireSeconds = root.path("expire").asLong(7200);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Feishu tenant_access_token is empty");
            }
            tenantToken = new TenantToken(token, now + expireSeconds * 1000);
            return token;
        }
    }

    private boolean isConfigured() {
        return properties.feishu().appId() != null && !properties.feishu().appId().isBlank()
                && properties.feishu().appSecret() != null && !properties.feishu().appSecret().isBlank();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void assertFeishuOk(Map<String, Object> response, String action) {
        JsonNode root = objectMapper.valueToTree(response);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String message = root.path("msg").asText("unknown error");
            throw new IllegalStateException("Feishu " + action + " failed: code=" + code + ", msg=" + message);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Feishu message content", e);
        }
    }

    private record TenantToken(String token, long expiresAtMillis) {}

    public record CardMetric(String label, String value) {}
}
