package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuEventControllerFeedbackTest {
    private final FeedbackService feedbackService = mock(FeedbackService.class);
    private final FeishuEventController controller = new FeishuEventController(
            mock(FeishuEventParser.class), mock(AgentTaskService.class), mock(FeishuClient.class),
            mock(TokenResolver.class), properties(), new ObjectMapper(), feedbackService
    );

    @Test
    void parsesNewCardCallbackAndFormValue() {
        when(feedbackService.handle(argThat(event ->
                event.action().equals("feedback_other_submit")
                        && event.requestId().equals("req-1")
                        && event.detail().equals("数据日期错误")
                        && event.openId().equals("ou-1")
                        && event.messageId().equals("om-1")
                        && event.eventId().equals("evt-1")
                        && event.eventTime() == 123456L
        ))).thenReturn(Map.of("toast", Map.of("type", "success", "content", "感谢你的反馈")));

        Object response = controller.receiveCardEvent(callback("verify-token"));

        assertEquals("感谢你的反馈", new ObjectMapper().valueToTree(response).at("/toast/content").asText());
    }

    @Test
    void rejectsMismatchedVerificationToken() {
        Object response = controller.receiveCardEvent(callback("wrong-token"));

        assertEquals("error", new ObjectMapper().valueToTree(response).at("/toast/type").asText());
        verify(feedbackService, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void convertsValidationFailureToErrorToast() {
        when(feedbackService.handle(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("请填写具体问题"));

        Object response = controller.receiveCardEvent(callback("verify-token"));

        assertEquals("请填写具体问题", new ObjectMapper().valueToTree(response).at("/toast/content").asText());
    }

    @Test
    void validatesTokenBeforeAnsweringChallenge() {
        Object rejected = controller.receiveCardEvent(Map.of("challenge", "abc", "token", "wrong-token"));
        Object accepted = controller.receiveCardEvent(Map.of("challenge", "abc", "token", "verify-token"));

        assertTrue(new ObjectMapper().valueToTree(rejected).path("challenge").isMissingNode());
        assertEquals("abc", new ObjectMapper().valueToTree(accepted).path("challenge").asText());
    }

    private Map<String, Object> callback(String token) {
        return Map.of(
                "schema", "2.0",
                "header", Map.of(
                        "token", token,
                        "event_type", "card.action.trigger",
                        "event_id", "evt-1",
                        "create_time", "123456"
                ),
                "event", Map.of(
                        "operator", Map.of("open_id", "ou-1"),
                        "context", Map.of("open_message_id", "om-1", "open_chat_id", "oc-1"),
                        "action", Map.of(
                                "tag", "button",
                                "value", Map.of(
                                        "action", "feedback_other_submit",
                                        "request_id", "req-1",
                                        "reason_code", "OTHER"
                                ),
                                "form_value", Map.of("feedback_detail", "数据日期错误")
                        )
                )
        );
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
