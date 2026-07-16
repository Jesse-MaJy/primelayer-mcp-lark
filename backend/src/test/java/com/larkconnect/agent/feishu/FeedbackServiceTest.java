package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AnswerPresentation;
import com.larkconnect.agent.agent.AnswerPresentationParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackServiceTest {
    private final AnswerFeedbackRepository repository = mock(AnswerFeedbackRepository.class);
    private final FeishuClient feishuClient = mock(FeishuClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(repository, feishuClient, new AnswerPresentationParser(objectMapper));
        when(repository.findAnswer("req-1")).thenReturn(Optional.of(new AnswerFeedbackRepository.AnswerContext(
                "req-1", "om-1", "问题", "回答",
                "{\"plainText\":\"回答\",\"markdown\":\"## 回答\",\"tables\":[],\"charts\":[]}",
                "项目数据分析", "blue"
        )));
        when(feishuClient.buildAnswerFeedbackCard(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("config", Map.of("update_multi", false)));
        when(feishuClient.buildAnswerFeedbackCard(anyString(), anyString(), any(AnswerPresentation.class),
                anyString(), anyString(), any())).thenReturn(Map.of("config", Map.of("update_multi", true)));
    }

    @Test
    void recordsHelpfulFeedbackAndReturnsUpdatedCard() {
        AnswerFeedbackRepository.FeedbackRecord saved = new AnswerFeedbackRepository.FeedbackRecord(
                "req-1", "ou-1", "HELPFUL", null, null, "om-1", "evt-1", 100, null
        );
        when(repository.upsert(any())).thenReturn(new AnswerFeedbackRepository.SaveOutcome(saved, true));

        Map<String, Object> response = service.handle(event("feedback_helpful", null, null));

        verify(repository).upsert(any());
        JsonNode json = objectMapper.valueToTree(response);
        assertEquals("success", json.at("/toast/type").asText());
        assertEquals("raw", json.at("/card/type").asText());
    }

    @Test
    void problemChoiceOnlyShowsReasonsWithoutWriting() {
        service.handle(event("feedback_problem", null, null));

        verify(repository, never()).upsert(any());
        verify(feishuClient).buildAnswerFeedbackCard(
                org.mockito.ArgumentMatchers.eq("req-1"), org.mockito.ArgumentMatchers.eq("问题"),
                org.mockito.ArgumentMatchers.argThat((AnswerPresentation value) ->
                        value.blocks().get(0).markdown().equals("## 回答")),
                org.mockito.ArgumentMatchers.eq("项目数据分析"), org.mockito.ArgumentMatchers.eq("blue"),
                org.mockito.ArgumentMatchers.eq(FeishuClient.AnswerFeedbackView.reasons())
        );
    }

    @Test
    void requiresDetailForOtherFeedback() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.handle(event("feedback_other_submit", "OTHER", "  ")));

        assertTrue(error.getMessage().contains("具体问题"));
        verify(repository, never()).upsert(any());
    }

    @Test
    void rejectsUnsupportedReason() {
        assertThrows(IllegalArgumentException.class,
                () -> service.handle(event("feedback_reason", "MADE_UP", null)));
        verify(repository, never()).upsert(any());
    }

    @Test
    void staleCallbackKeepsLatestStoredFeedback() {
        AnswerFeedbackRepository.FeedbackRecord latest = new AnswerFeedbackRepository.FeedbackRecord(
                "req-1", "ou-1", "PROBLEM", "INCOMPLETE", null, "om-1", "evt-2", 200, null
        );
        when(repository.upsert(any())).thenReturn(new AnswerFeedbackRepository.SaveOutcome(latest, false));

        Map<String, Object> response = service.handle(event("feedback_helpful", null, null));

        JsonNode json = objectMapper.valueToTree(response);
        assertEquals("评价已是最新状态", json.at("/toast/content").asText());
        verify(feishuClient).buildAnswerFeedbackCard(
                org.mockito.ArgumentMatchers.eq("req-1"), org.mockito.ArgumentMatchers.eq("问题"),
                org.mockito.ArgumentMatchers.any(AnswerPresentation.class),
                org.mockito.ArgumentMatchers.eq("项目数据分析"), org.mockito.ArgumentMatchers.eq("blue"),
                org.mockito.ArgumentMatchers.eq(FeishuClient.AnswerFeedbackView.submitted("PROBLEM", "INCOMPLETE", null))
        );
    }

    private FeedbackService.FeedbackEvent event(String action, String reasonCode, String detail) {
        return new FeedbackService.FeedbackEvent(
                action, "req-1", reasonCode, detail, "ou-1", "om-1", "evt-1", 100
        );
    }
}
