package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuEventControllerMessageReactionTest {

    @Test
    void addsGetReactionBeforePublishingAcceptedMessage() {
        FeishuEventParser parser = mock(FeishuEventParser.class);
        AgentTaskService taskService = mock(AgentTaskService.class);
        FeishuClient feishuClient = mock(FeishuClient.class);
        FeishuIncomingMessage message = new FeishuIncomingMessage(
                "om-1", "ou-1", "oc-1", "p2p", "查询项目进度");
        when(parser.parse(Map.of("event", "payload"))).thenReturn(message);
        FeishuEventController controller = controller(parser, taskService, feishuClient);

        controller.receive(Map.of("event", "payload"));

        verify(feishuClient).addReaction("om-1", "Get");
        verify(taskService).createAndPublish(message);
    }

    @Test
    void reactionFailureDoesNotBlockPublishingAcceptedMessage() {
        FeishuEventParser parser = mock(FeishuEventParser.class);
        AgentTaskService taskService = mock(AgentTaskService.class);
        FeishuClient feishuClient = mock(FeishuClient.class);
        FeishuIncomingMessage message = new FeishuIncomingMessage(
                "om-2", "ou-1", "oc-1", "p2p", "查询项目进度");
        when(parser.parse(Map.of("event", "payload"))).thenReturn(message);
        doThrow(new IllegalStateException("reaction unavailable"))
                .when(feishuClient).addReaction("om-2", "Get");
        FeishuEventController controller = controller(parser, taskService, feishuClient);

        controller.receive(Map.of("event", "payload"));

        verify(taskService).createAndPublish(message);
    }

    private FeishuEventController controller(FeishuEventParser parser, AgentTaskService taskService,
                                             FeishuClient feishuClient) {
        return new FeishuEventController(
                parser, taskService, feishuClient, mock(TokenResolver.class), properties(),
                new ObjectMapper(), mock(FeedbackService.class)
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
