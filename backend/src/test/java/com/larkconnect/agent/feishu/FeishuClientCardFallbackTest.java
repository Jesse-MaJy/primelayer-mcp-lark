package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AnswerPresentation;
import com.larkconnect.agent.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class FeishuClientCardFallbackTest {
    @Test
    void retriesCardValidationFailureWithMarkdownOnlyCard() {
        StubClient client = client(new FeishuClient.FeishuApiException(
                230001, "invalid card content", "reply"));

        FeishuClient.DeliveryResult result = client.replyAnswerFeedbackCard(
                "m1", "r1", "问题", AnswerPresentation.markdownOnly("## 回答"), "AI 回答", "blue");

        assertThat(client.calls).isEqualTo(2);
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.warning()).contains("已降级为 Markdown");
    }

    @Test
    void translatesHttpCardTableLimitAndProvidesReadableReason() {
        String response = """
                {"code":230099,"msg":"Failed to create card content, ext=ErrCode: 11310; ErrMsg: card table number over limit; ErrorValue: table;"}
                """;
        HttpClientErrorException httpError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                response.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        FeishuClient.FeishuApiException error = client(null).translateHttpFailure(httpError, "reply card message");

        assertThat(error.code()).isEqualTo(230099);
        assertThat(error.cardContentError()).isTrue();
        assertThat(FeishuClient.deliveryFailureReason(error))
                .isEqualTo("卡片表格数量超过飞书上限（最多 5 个）");
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
