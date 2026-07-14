package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeishuWsEchoBotMessageReactionTest {

    @Test
    void addsGetReactionBeforePublishingAcceptedMessage() throws Throwable {
        AgentTaskService taskService = mock(AgentTaskService.class);
        FeishuClient feishuClient = mock(FeishuClient.class);
        EventDispatcher dispatcher = bot(taskService, feishuClient).buildEventDispatcher();

        dispatcher.doWithoutValidation(messageEventJson("om-1").getBytes(StandardCharsets.UTF_8));

        verify(feishuClient).addReaction("om-1", "Get");
        verify(taskService).createAndPublish(argThat(message ->
                message.messageId().equals("om-1") && message.text().equals("查询项目进度")));
    }

    @Test
    void reactionFailureDoesNotBlockPublishingAcceptedMessage() throws Throwable {
        AgentTaskService taskService = mock(AgentTaskService.class);
        FeishuClient feishuClient = mock(FeishuClient.class);
        doThrow(new IllegalStateException("reaction unavailable"))
                .when(feishuClient).addReaction("om-2", "Get");
        EventDispatcher dispatcher = bot(taskService, feishuClient).buildEventDispatcher();

        dispatcher.doWithoutValidation(messageEventJson("om-2").getBytes(StandardCharsets.UTF_8));

        verify(taskService).createAndPublish(argThat(message -> message.messageId().equals("om-2")));
    }

    private FeishuWsEchoBot bot(AgentTaskService taskService, FeishuClient feishuClient) {
        return new FeishuWsEchoBot(
                properties(), taskService, feishuClient, mock(FeedbackService.class), new ObjectMapper()
        );
    }

    private String messageEventJson(String messageId) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                    "schema", "2.0",
                    "header", Map.of(
                            "event_id", "evt-1",
                            "event_type", "im.message.receive_v1",
                            "create_time", "123456"
                    ),
                    "event", Map.of(
                            "sender", Map.of(
                                    "sender_id", Map.of("open_id", "ou-1"),
                                    "sender_type", "user"
                            ),
                            "message", Map.of(
                                    "message_id", messageId,
                                    "create_time", "123456",
                                    "chat_id", "oc-1",
                                    "chat_type", "p2p",
                                    "message_type", "text",
                                    "content", "{\"text\":\"查询项目进度\"}"
                            )
                    )
            ));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
