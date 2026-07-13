package com.larkconnect.agent.feishu;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class FeedbackService {
    private static final Set<String> QUICK_REASONS = Set.of("DATA_INACCURATE", "OFF_TOPIC", "INCOMPLETE");
    private final AnswerFeedbackRepository repository;
    private final FeishuClient feishuClient;

    public FeedbackService(AnswerFeedbackRepository repository, FeishuClient feishuClient) {
        this.repository = repository;
        this.feishuClient = feishuClient;
    }

    public Map<String, Object> handle(FeedbackEvent event) {
        validateEvent(event);
        AnswerFeedbackRepository.AnswerContext answer = repository.findAnswer(event.requestId())
                .orElseThrow(() -> new IllegalArgumentException("未找到可评价的 AI 回答"));

        return switch (event.action()) {
            case "feedback_problem", "feedback_back_reasons" -> cardResponse(answer,
                    FeishuClient.AnswerFeedbackView.reasons(), null, null);
            case "feedback_other_form" -> cardResponse(answer,
                    FeishuClient.AnswerFeedbackView.otherForm(), null, null);
            case "feedback_edit" -> cardResponse(answer,
                    FeishuClient.AnswerFeedbackView.initial(), null, null);
            case "feedback_helpful" -> save(answer, event, "HELPFUL", null, null);
            case "feedback_reason" -> {
                if (!QUICK_REASONS.contains(event.reasonCode())) {
                    throw new IllegalArgumentException("不支持的问题原因");
                }
                yield save(answer, event, "PROBLEM", event.reasonCode(), null);
            }
            case "feedback_other_submit" -> {
                String detail = cleanDetail(event.detail());
                yield save(answer, event, "PROBLEM", "OTHER", detail);
            }
            default -> throw new IllegalArgumentException("不支持的反馈动作");
        };
    }

    private Map<String, Object> save(AnswerFeedbackRepository.AnswerContext answer, FeedbackEvent event,
                                     String rating, String reasonCode, String detail) {
        AnswerFeedbackRepository.SaveOutcome outcome = repository.upsert(new AnswerFeedbackRepository.FeedbackWrite(
                event.requestId(), event.openId(), rating, reasonCode, detail,
                hasText(event.messageId()) ? event.messageId() : answer.messageId(),
                event.eventId(), event.eventTime()
        ));
        AnswerFeedbackRepository.FeedbackRecord record = outcome.record();
        String toast = outcome.applied() ? "感谢你的反馈" : "评价已是最新状态";
        return cardResponse(answer, FeishuClient.AnswerFeedbackView.submitted(
                record.rating(), record.reasonCode(), record.detail()), "success", toast);
    }

    private Map<String, Object> cardResponse(AnswerFeedbackRepository.AnswerContext answer,
                                             FeishuClient.AnswerFeedbackView view,
                                             String toastType, String toastContent) {
        Map<String, Object> card = feishuClient.buildAnswerFeedbackCard(
                answer.requestId(), answer.question(), answer.answer(), answer.title(), answer.template(), view);
        java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>();
        if (hasText(toastContent)) {
            response.put("toast", Map.of("type", toastType, "content", toastContent));
        }
        response.put("card", Map.of("type", "raw", "data", card));
        return response;
    }

    private void validateEvent(FeedbackEvent event) {
        if (event == null || !hasText(event.action()) || !hasText(event.requestId())
                || !hasText(event.openId()) || !hasText(event.eventId()) || event.eventTime() <= 0) {
            throw new IllegalArgumentException("反馈事件缺少必要字段");
        }
    }

    private String cleanDetail(String detail) {
        String cleaned = detail == null ? "" : detail.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("请填写具体问题");
        }
        if (cleaned.length() > 500) {
            throw new IllegalArgumentException("具体问题不能超过 500 字");
        }
        return cleaned;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record FeedbackEvent(String action, String requestId, String reasonCode, String detail,
                                String openId, String messageId, String eventId, long eventTime) {}
}
