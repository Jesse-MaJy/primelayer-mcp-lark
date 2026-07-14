package com.larkconnect.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.feishu.FeishuClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class DeliveryOutboxDispatcher {
    private final DeliveryOutboxRepository outbox;
    private final FeishuClient feishu;
    private final ObjectMapper mapper;
    private final AnswerPresentationParser presentationParser;
    private final Clock clock;

    public DeliveryOutboxDispatcher(DeliveryOutboxRepository outbox, FeishuClient feishu,
                                    ObjectMapper mapper, AnswerPresentationParser presentationParser, Clock clock) {
        this.outbox = outbox; this.feishu = feishu; this.mapper = mapper;
        this.presentationParser = presentationParser; this.clock = clock;
    }

    @Scheduled(fixedDelay = 1_000, initialDelay = 1_000)
    public void dispatchDue() {
        for (int i = 0; i < 20; i++) {
            Instant now = clock.instant();
            DeliveryOutboxEntry entry = outbox.claimDue(now, now.plusSeconds(30)).orElse(null);
            if (entry == null) return;
            try {
                Map<String, Object> payload = mapper.readValue(entry.payloadJson(), new TypeReference<>() {});
                String sentMessageId;
                if (entry.deliveryType() == DeliveryType.FINAL_RESULT) {
                    String answer = String.valueOf(payload.get("answer"));
                    AnswerPresentation presentation = presentationParser.parse(
                            payload.get("presentationJson") == null ? null : String.valueOf(payload.get("presentationJson")),
                            answer).presentation();
                    FeishuClient.DeliveryResult delivery = feishu.replyAnswerFeedbackCard(
                            String.valueOf(payload.get("messageId")), entry.requestId(),
                            String.valueOf(payload.get("question")), presentation,
                            String.valueOf(payload.get("title")), String.valueOf(payload.get("template")));
                    sentMessageId = delivery == null ? null : delivery.messageId();
                } else {
                    sentMessageId = feishu.replyAnswerCard(String.valueOf(payload.get("messageId")),
                            String.valueOf(payload.get("question")),
                            String.valueOf(payload.get("answer")), String.valueOf(payload.get("title")),
                            String.valueOf(payload.get("template")));
                }
                outbox.markSent(entry.id(), sentMessageId, clock.instant());
            } catch (Exception failure) {
                long seconds = Math.min(300, 1L << Math.min(entry.attempts(), 8));
                outbox.markRetry(entry.id(), readable(failure), clock.instant().plus(Duration.ofSeconds(seconds)));
            }
        }
    }

    private String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
