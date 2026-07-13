package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class FeishuClientCardFallbackTest {
    @Test
    void retriesCardValidationFailureWithMarkdownOnlyCard() {
        StubClient client = client(new FeishuClient.FeishuApiException(
                230001, "invalid card content", "reply"));

        client.replyAnswerCard("m1", "问题", "## 回答", "AI 回答", "blue");

        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void doesNotRetryTransportFailure() {
        StubClient client = client(new IllegalStateException("network unavailable"));

        assertThatThrownBy(() -> client.replyAnswerCard("m1", "问题", "回答", "AI 回答", "blue"))
                .hasMessageContaining("network unavailable");
        assertThat(client.calls).isEqualTo(1);
    }

    private StubClient client(RuntimeException firstFailure) {
        AppProperties properties = new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(5, 1000, 1000),
                new AppProperties.Feishu("", "", "verify-token", "", false),
                new AppProperties.DeepSeek("", ""),
                new AppProperties.Mcp("", "Authorization")
        );
        return new StubClient(properties, firstFailure);
    }

    private static final class StubClient extends FeishuClient {
        private final RuntimeException firstFailure;
        private int calls;

        private StubClient(AppProperties properties, RuntimeException firstFailure) {
            super(properties, new ObjectMapper(), RestClient.builder(), new FeishuAnswerCardRenderer());
            this.firstFailure = firstFailure;
        }

        @Override
        public Map<String, Object> replyCard(String messageId, Map<String, Object> card) {
            calls++;
            if (calls == 1) throw firstFailure;
            return Map.of("code", 0);
        }
    }
}
