package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeishuWsEchoBotCardActionTest {

    @Test
    void dispatchesCardActionTriggerReceivedFromWebSocket() throws Throwable {
        FeedbackService feedbackService = mock(FeedbackService.class);
        when(feedbackService.handle(argThat(event ->
                event.action().equals("feedback_problem")
                        && event.requestId().equals("req-1")
                        && event.openId().equals("ou-1")
                        && event.messageId().equals("om-1")
                        && event.eventId().equals("evt-1")
                        && event.eventTime() == 123456L
        ))).thenReturn(Map.of(
                "card", Map.of("type", "raw", "data", Map.of("config", Map.of("update_multi", false)))
        ));
        FeishuWsEchoBot bot = new FeishuWsEchoBot(
                properties(), mock(AgentTaskService.class), mock(FeishuClient.class),
                feedbackService, new ObjectMapper()
        );
        EventDispatcher dispatcher = bot.buildEventDispatcher();

        Object result = dispatcher.doWithoutValidation(cardCallbackJson().getBytes(StandardCharsets.UTF_8));

        P2CardActionTriggerResponse response = (P2CardActionTriggerResponse) result;
        assertEquals("raw", response.getCard().getType());
    }

    private String cardCallbackJson() {
        return """
                {
                  "schema": "2.0",
                  "header": {
                    "event_id": "evt-1",
                    "event_type": "card.action.trigger",
                    "create_time": "123456"
                  },
                  "event": {
                    "token": "verify-token",
                    "operator": {"open_id": "ou-1"},
                    "context": {"open_message_id": "om-1", "open_chat_id": "oc-1"},
                    "action": {
                      "tag": "button",
                      "value": {"action": "feedback_problem", "request_id": "req-1"}
                    }
                  }
                }
                """;
    }

    private AppProperties properties() {
        return new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(5, 1000, 1000),
                new AppProperties.Feishu("", "", "verify-token", "", false),
                new AppProperties.DeepSeek("", ""),
                new AppProperties.Mcp("", "Authorization")
        );
    }
}
