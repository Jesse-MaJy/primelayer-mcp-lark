package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.feishu.FeishuClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryOutboxDispatcherTest {
    @Test
    void sendsStructuredFinalResultAndMarksOutboxSent() throws Exception {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        ObjectMapper mapper = new ObjectMapper();
        DeliveryOutboxRepository outbox = mock(DeliveryOutboxRepository.class);
        FeishuClient feishu = mock(FeishuClient.class);
        String payload = mapper.writeValueAsString(java.util.Map.of(
                "messageId", "m1", "requestId", "r1", "question", "问题", "answer", "完成",
                "presentationJson", "{\"plainText\":\"完成\",\"blocks\":[{\"type\":\"markdown\",\"content\":\"完成\"}]}",
                "title", "项目数据分析", "template", "blue"));
        DeliveryOutboxEntry entry = new DeliveryOutboxEntry(1, "r1", DeliveryType.FINAL_RESULT,
                payload, "SENDING", 1, now, now.plusSeconds(30), null, null);
        when(outbox.claimDue(now, now.plusSeconds(30))).thenReturn(Optional.of(entry), Optional.empty());

        new DeliveryOutboxDispatcher(outbox, feishu, mapper, new AnswerPresentationParser(mapper),
                Clock.fixed(now, ZoneOffset.UTC)).dispatchDue();

        verify(feishu).replyAnswerFeedbackCard(eq("m1"), eq("r1"), eq("问题"),
                org.mockito.ArgumentMatchers.any(AnswerPresentation.class), eq("项目数据分析"), eq("blue"));
        verify(outbox).markSent(eq(1L), isNull(), eq(now));
    }
}
