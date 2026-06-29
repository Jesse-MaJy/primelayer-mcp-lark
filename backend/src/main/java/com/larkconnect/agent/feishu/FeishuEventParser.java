package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FeishuEventParser {
    private final ObjectMapper objectMapper;

    public FeishuEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FeishuIncomingMessage parse(Map<String, Object> body) {
        JsonNode root = objectMapper.valueToTree(body);
        JsonNode event = root.path("event");
        if (event.isMissingNode()) {
            return null;
        }
        JsonNode message = event.path("message");
        String messageType = message.path("message_type").asText();
        if (!"text".equals(messageType)) {
            return null;
        }
        String chatType = message.path("chat_type").asText("p2p");
        if ("group".equals(chatType) && !message.path("mentions").isArray()) {
            return null;
        }
        String text = extractText(message.path("content").asText("{}"));
        if (text == null || text.isBlank()) {
            return null;
        }
        return new FeishuIncomingMessage(
                message.path("message_id").asText(),
                event.path("sender").path("sender_id").path("open_id").asText(),
                message.path("chat_id").asText(),
                chatType,
                text
        );
    }

    private String extractText(String contentJson) {
        try {
            return objectMapper.readTree(contentJson).path("text").asText();
        } catch (Exception e) {
            return contentJson;
        }
    }
}
