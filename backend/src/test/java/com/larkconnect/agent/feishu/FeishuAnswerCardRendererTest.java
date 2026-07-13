package com.larkconnect.agent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.AnswerPresentation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuAnswerCardRendererTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeishuAnswerCardRenderer renderer = new FeishuAnswerCardRenderer();

    @Test
    void rendersJsonTwoMarkdownTableAndLineChart() {
        AnswerPresentation presentation = new AnswerPresentation("结论", "## 结论\n**6** 条缺陷", List.of(
                new AnswerPresentation.AnswerTable("明细", List.of(
                        new AnswerPresentation.TableColumn("name", "名称"),
                        new AnswerPresentation.TableColumn("count", "数量")
                ), List.of(Map.of("name", "C3", "count", "3")), 25)
        ), List.of(new AnswerPresentation.AnswerChart("缺陷趋势", AnswerPresentation.ChartType.LINE, List.of(
                new AnswerPresentation.ChartSeries("缺陷", List.of(
                        new AnswerPresentation.ChartPoint("7月1日", new BigDecimal("2")),
                        new AnswerPresentation.ChartPoint("7月2日", new BigDecimal("6"))
                ))
        ))));

        JsonNode card = objectMapper.valueToTree(renderer.answerCard(
                "req-1", "质量日报", presentation, "项目数据分析", "blue",
                FeishuClient.AnswerFeedbackView.initial()));

        assertThat(card.path("schema").asText()).isEqualTo("2.0");
        assertThat(card.at("/config/update_multi").asBoolean()).isTrue();
        assertThat(card.has("elements")).isFalse();
        assertThat(card.at("/body/elements").isArray()).isTrue();
        assertThat(card.toString()).contains("## 结论");
        assertThat(find(card, "tag", "markdown").path("content").asText()).isNotBlank();
        assertThat(card.toString()).doesNotContain("lark_md");
        JsonNode table = find(card, "tag", "table");
        assertThat(table.path("columns").get(0).path("display_name").asText()).isEqualTo("名称");
        assertThat(table.path("rows").get(0).path("name").asText()).isEqualTo("C3");
        assertThat(card.toString()).contains("共 25 条，仅展示前 20 条");
        JsonNode chart = find(card, "tag", "chart");
        assertThat(chart.path("preview").asBoolean()).isTrue();
        assertThat(chart.at("/chart_spec/type").asText()).isEqualTo("line");
        assertThat(chart.at("/chart_spec/xField").asText()).isEqualTo("label");
        assertThat(chart.at("/chart_spec/yField").asText()).isEqualTo("value");
        JsonNode feedbackButton = find(card, "tag", "button");
        assertThat(feedbackButton.has("value")).isFalse();
        assertThat(feedbackButton.at("/behaviors/0/type").asText()).isEqualTo("callback");
        assertThat(feedbackButton.at("/behaviors/0/value/request_id").asText()).isEqualTo("req-1");
    }

    @Test
    void createsWhitelistedPieSpecWithoutExecutableContent() {
        AnswerPresentation presentation = new AnswerPresentation("占比", "占比", List.of(), List.of(
                new AnswerPresentation.AnswerChart("占比", AnswerPresentation.ChartType.PIE, List.of(
                        new AnswerPresentation.ChartSeries("类型", List.of(
                                new AnswerPresentation.ChartPoint("A", new BigDecimal("3")),
                                new AnswerPresentation.ChartPoint("B", new BigDecimal("1"))
                        )))
        )));

        JsonNode card = objectMapper.valueToTree(renderer.answerCard(
                null, "问题", presentation, "回答", "blue", null));
        JsonNode spec = find(card, "tag", "chart").path("chart_spec");

        assertThat(spec.path("type").asText()).isEqualTo("pie");
        assertThat(spec.path("categoryField").asText()).isEqualTo("label");
        assertThat(spec.path("valueField").asText()).isEqualTo("value");
        assertThat(spec.toString()).doesNotContain("javascript").doesNotContain("function");
    }

    @Test
    void createsBarSpecFromNormalizedPoints() {
        AnswerPresentation presentation = new AnswerPresentation("比较", "比较", List.of(), List.of(
                new AnswerPresentation.AnswerChart("承包商比较", AnswerPresentation.ChartType.BAR, List.of(
                        new AnswerPresentation.ChartSeries("缺陷", List.of(
                                new AnswerPresentation.ChartPoint("C3", new BigDecimal("3"))
                        )))
        )));

        JsonNode spec = find(objectMapper.valueToTree(renderer.answerCard(
                null, "问题", presentation, "回答", "blue", null)), "tag", "chart").path("chart_spec");

        assertThat(spec.path("type").asText()).isEqualTo("bar");
        assertThat(spec.path("seriesField").asText()).isEqualTo("series");
        assertThat(spec.at("/data/values/0/label").asText()).isEqualTo("C3");
    }

    private JsonNode find(JsonNode root, String key, String value) {
        if (root.isObject() && value.equals(root.path(key).asText())) return root;
        for (JsonNode child : root) {
            JsonNode found = find(child, key, value);
            if (!found.isMissingNode()) return found;
        }
        return objectMapper.missingNode();
    }
}
