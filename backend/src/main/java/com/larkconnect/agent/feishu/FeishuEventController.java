package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.common.ApiResponse;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/feishu")
public class FeishuEventController {
    private static final Logger log = LoggerFactory.getLogger(FeishuEventController.class);
    private final FeishuEventParser parser;
    private final AgentTaskService taskService;
    private final FeishuClient feishuClient;
    private final TokenResolver tokenResolver;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public FeishuEventController(
            FeishuEventParser parser,
            AgentTaskService taskService,
            FeishuClient feishuClient,
            TokenResolver tokenResolver,
            AppProperties properties,
            ObjectMapper objectMapper
    ) {
        this.parser = parser;
        this.taskService = taskService;
        this.feishuClient = feishuClient;
        this.tokenResolver = tokenResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/events")
    public Object receive(@RequestBody Map<String, Object> body) {
        log.info("Received Feishu event keys: {}", body.keySet());
        if (body.containsKey("challenge")) {
            log.info("Feishu event challenge received");
            return Map.of("challenge", body.get("challenge"));
        }
        if (body.containsKey("encrypt")) {
            log.warn("Received encrypted Feishu event, but decrypt is not implemented yet. Disable Encrypt Key for echo test.");
            return ApiResponse.error("收到飞书加密事件，但当前 Echo 测试未启用解密。请先在飞书事件订阅中关闭 Encrypt Key。");
        }
        FeishuIncomingMessage message = parser.parse(body);
        if (message == null) {
            log.info("Feishu event ignored. Body: {}", body);
            return ApiResponse.ok("ignored");
        }
        log.info("Parsed Feishu message: messageId={}, chatType={}, chatId={}, openId={}, text={}",
                message.messageId(), message.chatType(), message.chatId(), message.openId(), message.text());
        if ("p2p".equals(message.chatType()) && isStartupKeyword(message.text())) {
            feishuClient.replyWelcomeCard(message.messageId());
            return ApiResponse.ok("welcome_sent");
        }
        taskService.createAndPublish(message);
        return ApiResponse.ok("accepted");
    }

    @PostMapping("/card-events")
    public Object receiveCardEvent(@RequestBody Map<String, Object> body) {
        log.info("Received Feishu card event keys: {}", body.keySet());
        if (body.containsKey("challenge")) {
            return Map.of("challenge", body.get("challenge"));
        }
        if (body.containsKey("encrypt")) {
            return ApiResponse.error("收到飞书加密卡片事件，但当前未启用解密。请先关闭 Encrypt Key 或补充解密能力。");
        }
        JsonNode root = objectMapper.valueToTree(body);
        String action = firstText(root,
                "/event/action/value/action",
                "/event/action/action",
                "/action/value/action",
                "/action/action"
        );
        if ("preset_question".equals(action)) {
            String openId = firstText(root,
                    "/event/operator/open_id",
                    "/event/operator/operator_id/open_id",
                    "/operator/open_id",
                    "/operator/operator_id/open_id"
            );
            String chatId = firstText(root,
                    "/event/context/open_chat_id",
                    "/event/message/chat_id",
                    "/context/open_chat_id"
            );
            String receiveIdType = hasText(openId) ? "open_id" : "chat_id";
            String receiveId = hasText(openId) ? openId : chatId;
            String question = firstText(root,
                    "/event/action/value/question",
                    "/action/value/question"
            );
            if (!hasText(receiveId) || !hasText(question)) {
                return ApiResponse.error("卡片事件缺少 open_id 或 question，无法发送预设问题。");
            }
            feishuClient.sendQuestionPromptCard(receiveIdType, receiveId, question);
            return ApiResponse.ok("accepted");
        }
        if (!"check_mcp_config".equals(action)) {
            log.info("Feishu card event ignored because action={}", action);
            return ApiResponse.ok("ignored");
        }
        String openId = firstText(root,
                "/event/operator/open_id",
                "/event/operator/operator_id/open_id",
                "/operator/open_id",
                "/operator/operator_id/open_id"
        );
        String chatId = firstText(root,
                "/event/context/open_chat_id",
                "/event/message/chat_id",
                "/context/open_chat_id"
        );
        String chatType = firstText(root,
                "/event/message/chat_type",
                "/event/context/chat_type",
                "/message/chat_type"
        );
        if (!hasText(chatType)) {
            chatType = "p2p";
        }
        TokenResolver.McpConfigCheckResult result = tokenResolver.checkMcpConfig(
                openId,
                chatId,
                chatType,
                properties.agent().maxProjectsPerQuery()
        );
        String receiveIdType = hasText(openId) ? "open_id" : "chat_id";
        String receiveId = hasText(openId) ? openId : chatId;
        if (!hasText(receiveId)) {
            return ApiResponse.error("卡片事件缺少 open_id，无法推送配置检查结果。");
        }
        if (result.configured()) {
            feishuClient.sendProjectEntryCard(receiveIdType, receiveId, result);
        } else {
            feishuClient.sendMcpRequiredCard(receiveIdType, receiveId, result);
        }
        return ApiResponse.ok("accepted");
    }

    private boolean isStartupKeyword(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase();
        return normalized.equals("开始")
                || normalized.equals("帮助")
                || normalized.equals("配置")
                || normalized.equals("绑定")
                || normalized.equals("start")
                || normalized.equals("/start")
                || normalized.equals("help")
                || normalized.equals("/help");
    }

    private String firstText(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (!node.isMissingNode() && !node.isNull() && hasText(node.asText())) {
                return node.asText();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
