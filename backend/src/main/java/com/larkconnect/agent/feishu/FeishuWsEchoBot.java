package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class FeishuWsEchoBot {
    private static final Logger log = LoggerFactory.getLogger(FeishuWsEchoBot.class);
    private final AppProperties properties;
    private final AgentTaskService taskService;
    private final FeishuClient feishuClient;
    private final FeedbackService feedbackService;
    private final ObjectMapper objectMapper;

    public FeishuWsEchoBot(
            AppProperties properties,
            AgentTaskService taskService,
            FeishuClient feishuClient,
            FeedbackService feedbackService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.taskService = taskService;
        this.feishuClient = feishuClient;
        this.feedbackService = feedbackService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        if (!properties.feishu().echoEnabled()) {
            log.info("Feishu WS bot disabled because FEISHU_ECHO_ENABLED=false");
            return;
        }
        if (!hasText(properties.feishu().appId()) || !hasText(properties.feishu().appSecret())) {
            log.info("Feishu WS bot disabled because App ID or App Secret is not configured");
            return;
        }
        EventDispatcher eventHandler = buildEventDispatcher();
        com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(
                properties.feishu().appId(),
                properties.feishu().appSecret()
        ).eventHandler(eventHandler).build();
        Thread thread = new Thread(wsClient::start, "feishu-ws-echo-bot");
        thread.setDaemon(false);
        thread.start();
        log.info("Feishu WS bot started");
    }

    EventDispatcher buildEventDispatcher() {
        return EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        handleMessage(event);
                    }
                })
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                        return handleCardAction(event);
                    }
                })
                .build();
    }

    private P2CardActionTriggerResponse handleCardAction(P2CardActionTrigger event) {
        try {
            if (event == null || event.getEvent() == null || event.getEvent().getAction() == null) {
                return cardError("卡片事件内容不完整");
            }
            String callbackToken = hasText(event.getEvent().getToken())
                    ? event.getEvent().getToken()
                    : event.getHeader() == null ? "" : event.getHeader().getToken();
            if (!properties.feishu().verificationToken().equals(callbackToken)) {
                return cardError("飞书卡片回调校验失败");
            }
            CallBackAction callbackAction = event.getEvent().getAction();
            Map<String, Object> value = callbackAction.getValue() == null ? Map.of() : callbackAction.getValue();
            String action = stringValue(value.get("action"));
            if (!action.startsWith("feedback_")) {
                return cardError("不支持的卡片操作");
            }
            Map<String, Object> formValue = callbackAction.getFormValue() == null
                    ? Map.of()
                    : callbackAction.getFormValue();
            Map<String, Object> result = feedbackService.handle(new FeedbackService.FeedbackEvent(
                    action,
                    stringValue(value.get("request_id")),
                    stringValue(value.get("reason_code")),
                    stringValue(formValue.get("feedback_detail")),
                    event.getEvent().getOperator() == null ? "" : event.getEvent().getOperator().getOpenId(),
                    event.getEvent().getContext() == null ? "" : event.getEvent().getContext().getOpenMessageId(),
                    event.getHeader() == null ? "" : event.getHeader().getEventId(),
                    event.getHeader() == null ? 0L : longValue(event.getHeader().getCreateTime())
            ));
            return objectMapper.convertValue(result, P2CardActionTriggerResponse.class);
        } catch (IllegalArgumentException e) {
            log.info("Feishu WS feedback rejected: {}", e.getMessage());
            return cardError(e.getMessage());
        } catch (Exception e) {
            log.error("Feishu WS feedback failed", e);
            return cardError("反馈提交失败，请稍后重试");
        }
    }

    private P2CardActionTriggerResponse cardError(String message) {
        return objectMapper.convertValue(Map.of(
                "toast", Map.of("type", "error", "content", hasText(message) ? message : "卡片操作失败")
        ), P2CardActionTriggerResponse.class);
    }

    private long longValue(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void handleMessage(P2MessageReceiveV1 event) {
        log.info("[onP2MessageReceiveV1] event={}", Jsons.DEFAULT.toJson(event.getEvent()));
        if (!"text".equals(event.getEvent().getMessage().getMessageType())) {
            log.info("Feishu WS message ignored because messageType={}", event.getEvent().getMessage().getMessageType());
            return;
        }
        String content = event.getEvent().getMessage().getContent();
        Map<String, String> respContent = new HashMap<>();
        try {
            respContent = new Gson().fromJson(content, new TypeToken<Map<String, String>>() {}.getType());
        } catch (JsonSyntaxException e) {
            log.warn("Feishu WS message content parse failed. messageId={}", event.getEvent().getMessage().getMessageId());
            return;
        }
        String text = respContent.getOrDefault("text", "");
        if (!hasText(text)) {
            log.info("Feishu WS message ignored because text is empty. messageId={}", event.getEvent().getMessage().getMessageId());
            return;
        }
        if ("p2p".equals(event.getEvent().getMessage().getChatType()) && isStartupKeyword(text)) {
            feishuClient.replyWelcomeCard(event.getEvent().getMessage().getMessageId());
            log.info("Feishu WS welcome card sent. messageId={}", event.getEvent().getMessage().getMessageId());
            return;
        }
        FeishuIncomingMessage message = new FeishuIncomingMessage(
                event.getEvent().getMessage().getMessageId(),
                event.getEvent().getSender().getSenderId().getOpenId(),
                event.getEvent().getMessage().getChatId(),
                event.getEvent().getMessage().getChatType(),
                text
        );
        taskService.createAndPublish(message);
        log.info("Feishu WS message accepted for Agent. messageId={}, chatType={}, chatId={}",
                message.messageId(), message.chatType(), message.chatId());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
}
