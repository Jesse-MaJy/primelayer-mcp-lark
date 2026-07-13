package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuClientFeedbackCardTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeishuClient client = new FeishuClient(properties(), objectMapper, RestClient.builder());

    @Test
    void buildsInitialFeedbackButtonsForSuccessfulAnswer() {
        JsonNode card = json(client.buildAnswerFeedbackCard(
                "req-1", "项目风险有哪些？", "当前有 2 项风险。", "项目数据分析", "blue",
                FeishuClient.AnswerFeedbackView.initial()
        ));

        assertFalse(card.at("/config/update_multi").asBoolean(true));
        assertEquals("feedback_helpful", findAction(card, "👍 有帮助").path("action").asText());
        assertEquals("req-1", findAction(card, "👍 有帮助").path("request_id").asText());
        assertEquals("feedback_problem", findAction(card, "⚠️ 有问题").path("action").asText());
    }

    @Test
    void buildsProblemReasonsAndOtherDetailForm() {
        JsonNode reasons = json(client.buildAnswerFeedbackCard(
                "req-1", "问题", "回答", "DeepSeek 回答", "blue",
                FeishuClient.AnswerFeedbackView.reasons()
        ));
        assertEquals("DATA_INACCURATE", findAction(reasons, "数据不准确").path("reason_code").asText());
        assertEquals("feedback_other_form", findAction(reasons, "其他").path("action").asText());

        JsonNode form = json(client.buildAnswerFeedbackCard(
                "req-1", "问题", "回答", "DeepSeek 回答", "blue",
                FeishuClient.AnswerFeedbackView.otherForm()
        ));
        JsonNode input = findByName(form, "feedback_detail");
        assertEquals("input", input.path("tag").asText());
        assertTrue(input.path("required").asBoolean());
        assertEquals(500, input.path("max_length").asInt());
        assertEquals("feedback_other_submit", findAction(form, "提交反馈").path("action").asText());
        assertTrue(findButton(form, "提交反馈").path("complex_interaction").asBoolean());
        assertEquals("form_submit", findButton(form, "提交反馈").path("action_type").asText());
        assertEquals("feedback_submit", findButton(form, "提交反馈").path("name").asText());
        assertFalse(findByName(form, "feedback_form").toString().contains("feedback_back_reasons"));
        assertEquals("feedback_back_reasons", findAction(form, "返回原因").path("action").asText());
    }

    @Test
    void buildsSubmittedStateWithEditAction() {
        JsonNode card = json(client.buildAnswerFeedbackCard(
                "req-1", "问题", "回答", "DeepSeek 回答", "blue",
                FeishuClient.AnswerFeedbackView.submitted("PROBLEM", "OTHER", "关键数据日期不对")
        ));

        assertTrue(card.toString().contains("已记录：有问题 · 其他"));
        assertEquals("plain_text", findTextTag(card, "说明：关键数据日期不对"));
        assertEquals("feedback_edit", findAction(card, "修改评价").path("action").asText());
    }

    private JsonNode findAction(JsonNode root, String label) {
        JsonNode button = findButton(root, label);
        return button.isMissingNode() ? button : button.path("value");
    }

    private JsonNode findButton(JsonNode root, String label) {
        if (root.isObject() && label.equals(root.path("text").path("content").asText())) return root;
        for (JsonNode child : root) {
            JsonNode found = findButton(child, label);
            if (!found.isMissingNode()) {
                return found;
            }
        }
        return objectMapper.missingNode();
    }

    private JsonNode findByName(JsonNode root, String name) {
        if (root.isObject() && name.equals(root.path("name").asText())) {
            return root;
        }
        for (JsonNode child : root) {
            JsonNode found = findByName(child, name);
            if (!found.isMissingNode()) {
                return found;
            }
        }
        return objectMapper.missingNode();
    }

    private String findTextTag(JsonNode root, String content) {
        if (root.isObject() && content.equals(root.path("content").asText())) {
            return root.path("tag").asText();
        }
        for (JsonNode child : root) {
            String found = findTextTag(child, content);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private JsonNode json(Map<String, Object> value) {
        return objectMapper.valueToTree(value);
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
