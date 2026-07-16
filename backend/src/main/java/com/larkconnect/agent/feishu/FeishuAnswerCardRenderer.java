package com.larkconnect.agent.feishu;

import com.larkconnect.agent.agent.AnswerPresentation;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FeishuAnswerCardRenderer {
    private static final String CARD_SCHEMA_VERSION = "primelayer-ai-card/v2";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Map<String, Object> answerCard(String requestId, String question, AnswerPresentation presentation,
                                          String title, String template,
                                          FeishuClient.AnswerFeedbackView feedbackView) {
        AnswerPresentation safe = presentation == null ? AnswerPresentation.markdownOnly("-") : presentation;
        String generatedAt = LocalDateTime.now().format(TIME_FORMATTER);
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**来源**  Primelayer AI  \n**生成时间**  " + generatedAt));
        elements.add(divider());
        elements.add(markdown("**问题**\n" + text(question)));
        elements.add(divider());
        for (AnswerPresentation.ContentBlock block : safe.blocks()) {
            switch (block.type()) {
                case MARKDOWN -> elements.add(markdown(block.markdown()));
                case TABLE -> {
                    AnswerPresentation.AnswerTable table = block.table();
                    elements.add(divider());
                    elements.add(markdown("### " + table.title()));
                    if (table.totalRows() > table.rows().size()) {
                        elements.add(plain("共 " + table.totalRows() + " 条，仅展示前 20 条"));
                    }
                    elements.add(table(table));
                }
                case CHART -> {
                    elements.add(divider());
                    elements.add(chart(block.chart()));
                }
            }
        }
        if (feedbackView != null && requestId != null && !requestId.isBlank()) {
            elements.add(divider());
            elements.addAll(feedback(requestId, feedbackView));
        }
        elements.add(divider());
        elements.add(plain("由 Primelayer AI 生成 · " + generatedAt + " · " + CARD_SCHEMA_VERSION));
        return card(title, template, elements);
    }

    public Map<String, Object> markdownOnlyCard(String question, String answer, String title, String template) {
        return answerCard(null, question, AnswerPresentation.markdownOnly(answer), title, template, null);
    }

    public Map<String, Object> card(String title, String template, List<Object> elements) {
        return Map.of(
                "schema", "2.0",
                "config", Map.of("update_multi", true, "enable_forward", true),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", text(title)),
                        "template", text(template)
                ),
                "body", Map.of("elements", elements == null ? List.of() : elements)
        );
    }

    public Map<String, Object> markdown(String content) {
        return Map.of("tag", "markdown", "content", text(content));
    }

    public Map<String, Object> plain(String content) {
        return Map.of("tag", "div", "text", Map.of("tag", "plain_text", "content", text(content)));
    }

    public Map<String, Object> divider() {
        return Map.of("tag", "hr");
    }

    public Map<String, Object> button(String label, String action, String type, Map<String, Object> extraValue) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", action);
        if (extraValue != null) value.putAll(extraValue);
        return Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", label),
                "type", type,
                "behaviors", List.of(Map.of("type", "callback", "value", value))
        );
    }

    private Map<String, Object> table(AnswerPresentation.AnswerTable table) {
        List<Map<String, Object>> columns = table.columns().stream().map(column -> Map.<String, Object>of(
                "name", column.key(),
                "display_name", column.label(),
                "data_type", "markdown",
                "width", "auto",
                "vertical_align", "top"
        )).toList();
        return Map.of(
                "tag", "table",
                "page_size", 10,
                "row_height", "high",
                "freeze_first_column", columns.size() > 3,
                "header_style", Map.of("bold", true, "background_style", "grey", "text_align", "left"),
                "columns", columns,
                "rows", table.rows()
        );
    }

    private Map<String, Object> chart(AnswerPresentation.AnswerChart chart) {
        List<Map<String, Object>> values = new ArrayList<>();
        chart.series().forEach(series -> series.points().forEach(point -> values.add(Map.of(
                "label", point.label(), "value", point.value(), "series", series.name()))));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", chart.type().name().toLowerCase());
        spec.put("title", Map.of("text", chart.title()));
        spec.put("data", Map.of("values", values));
        if (chart.type() == AnswerPresentation.ChartType.PIE) {
            spec.put("categoryField", "label");
            spec.put("valueField", "value");
            spec.put("label", Map.of("visible", true));
            spec.put("legends", Map.of("visible", true, "orient", "bottom"));
        } else {
            spec.put("xField", "label");
            spec.put("yField", "value");
            spec.put("seriesField", "series");
            spec.put("label", Map.of("visible", true));
            spec.put("legends", Map.of("visible", chart.series().size() > 1, "orient", "bottom"));
        }
        return Map.of(
                "tag", "chart",
                "aspect_ratio", chart.type() == AnswerPresentation.ChartType.PIE ? "4:3" : "16:9",
                "color_theme", "brand",
                "preview", true,
                "chart_spec", spec
        );
    }

    private List<Object> feedback(String requestId, FeishuClient.AnswerFeedbackView view) {
        return switch (view.state()) {
            case INITIAL -> List.of(
                    markdown("**这个回答对你有帮助吗？**"),
                    button("👍 有帮助", "feedback_helpful", "primary", Map.of("request_id", requestId)),
                    button("⚠️ 有问题", "feedback_problem", "default", Map.of("request_id", requestId))
            );
            case REASONS -> List.of(
                    markdown("**请选择问题原因**"),
                    reason("数据不准确", "DATA_INACCURATE", requestId),
                    reason("答非所问", "OFF_TOPIC", requestId),
                    reason("内容不完整", "INCOMPLETE", requestId),
                    button("其他", "feedback_other_form", "default", Map.of("request_id", requestId)),
                    button("返回", "feedback_edit", "default", Map.of("request_id", requestId))
            );
            case OTHER_FORM -> List.of(
                    markdown("**请说明具体问题**"),
                    otherFeedbackForm(requestId),
                    button("返回原因", "feedback_back_reasons", "default", Map.of("request_id", requestId))
            );
            case SUBMITTED -> submitted(requestId, view);
        };
    }

    private Map<String, Object> reason(String label, String code, String requestId) {
        return button(label, "feedback_reason", "default", Map.of("request_id", requestId, "reason_code", code));
    }

    private List<Object> submitted(String requestId, FeishuClient.AnswerFeedbackView view) {
        String label = "HELPFUL".equals(view.rating()) ? "已记录：有帮助" : "已记录：有问题 · " + reasonLabel(view.reasonCode());
        List<Object> result = new ArrayList<>();
        result.add(markdown("**" + label + "**"));
        if (view.detail() != null && !view.detail().isBlank()) result.add(plain("说明：" + view.detail()));
        result.add(button("修改评价", "feedback_edit", "default", Map.of("request_id", requestId)));
        return result;
    }

    private String reasonLabel(String code) {
        return switch (code == null ? "" : code) {
            case "DATA_INACCURATE" -> "数据不准确";
            case "OFF_TOPIC" -> "答非所问";
            case "INCOMPLETE" -> "内容不完整";
            case "OTHER" -> "其他";
            default -> "有问题";
        };
    }

    private Map<String, Object> otherFeedbackForm(String requestId) {
        Map<String, Object> submit = new LinkedHashMap<>(button(
                "提交反馈", "feedback_other_submit", "primary", Map.of("request_id", requestId)));
        submit.put("name", "feedback_submit");
        submit.put("form_action_type", "submit");
        return Map.of(
                "tag", "form",
                "name", "feedback_form",
                "elements", List.of(
                        Map.of(
                                "tag", "input", "name", "feedback_detail", "required", true,
                                "input_type", "multiline_text", "rows", 3, "max_length", 500,
                                "placeholder", Map.of("tag", "plain_text", "content", "请输入具体问题（最多 500 字）")
                        ),
                        submit
                )
        );
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
