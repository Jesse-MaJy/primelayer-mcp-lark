package com.larkconnect.agent.feishu;

import com.larkconnect.agent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class FeishuClient {
    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);
    private final AppProperties properties;
    private final RestClient restClient;

    public FeishuClient(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl("https://open.feishu.cn").build();
    }

    public void sendText(String chatId, String text) {
        if (properties.feishu().appId() == null || properties.feishu().appId().isBlank()) {
            log.info("Feishu app is not configured. Would send to {}: {}", chatId, text);
            return;
        }
        log.info("Feishu sendText placeholder to {}: {}", chatId, text);
        // TODO: exchange tenant_access_token and call /open-apis/im/v1/messages?receive_id_type=chat_id.
    }
}
