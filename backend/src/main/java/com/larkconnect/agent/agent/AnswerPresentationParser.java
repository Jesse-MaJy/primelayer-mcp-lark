package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AnswerPresentationParser {
    static final int MAX_TABLE_ROWS = 20;
    static final int MAX_CHARTS = 3;
    static final int MAX_CHART_POINTS = 50;
    private final ObjectMapper objectMapper;

    public AnswerPresentationParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedPresentation parse(String json, String fallback) {
        if (json == null || json.isBlank()) return fallback(fallback);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) return fallback(fallback);
            String plainText = truncate(clean(root.path("plainText").asText(null), fallback), 10_000);
            String markdown = truncate(safeMarkdown(clean(root.path("markdown").asText(null), plainText)), 10_000);
            AnswerPresentation value = new AnswerPresentation(
                    plainText, markdown, parseTables(root.path("tables")), parseCharts(root.path("charts")));
            return new ParsedPresentation(value, objectMapper.writeValueAsString(value), false);
        } catch (Exception ignored) {
            return fallback(fallback);
        }
    }

    public String toJson(AnswerPresentation presentation) {
        try {
            return objectMapper.writeValueAsString(presentation);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化回答展示结构", e);
        }
    }

    private List<AnswerPresentation.AnswerTable> parseTables(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<AnswerPresentation.AnswerTable> tables = new ArrayList<>();
        for (JsonNode table : node) {
            List<AnswerPresentation.TableColumn> columns = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode column : table.path("columns")) {
                String key = column.path("key").asText("").trim();
                String label = column.path("label").asText("").trim();
                if (!key.isEmpty() && !label.isEmpty() && keys.add(key) && columns.size() < 8) {
                    columns.add(new AnswerPresentation.TableColumn(key, label));
                }
            }
            if (columns.isEmpty()) continue;
            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonNode row : table.path("rows")) {
                if (!row.isObject() || rows.size() >= MAX_TABLE_ROWS) break;
                Map<String, String> values = new LinkedHashMap<>();
                for (AnswerPresentation.TableColumn column : columns) {
                    values.put(column.key(), truncate(row.path(column.key()).asText(""), 1_000));
                }
                rows.add(values);
            }
            int totalRows = Math.max(table.path("totalRows").asInt(rows.size()), rows.size());
            tables.add(new AnswerPresentation.AnswerTable(
                    clean(table.path("title").asText(null), "明细"), columns, rows, totalRows));
        }
        return List.copyOf(tables);
    }

    private List<AnswerPresentation.AnswerChart> parseCharts(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<AnswerPresentation.AnswerChart> charts = new ArrayList<>();
        for (JsonNode chart : node) {
            if (charts.size() >= MAX_CHARTS) break;
            AnswerPresentation.ChartType type = chartType(chart.path("type").asText());
            if (type == null) continue;
            List<AnswerPresentation.ChartSeries> series = new ArrayList<>();
            for (JsonNode rawSeries : chart.path("series")) {
                List<AnswerPresentation.ChartPoint> points = new ArrayList<>();
                Set<String> labels = new LinkedHashSet<>();
                for (JsonNode point : rawSeries.path("points")) {
                    if (points.size() >= MAX_CHART_POINTS) break;
                    String label = point.path("label").asText("").trim();
                    BigDecimal value = decimal(point.path("value"));
                    if (!label.isEmpty() && value != null && labels.add(label)) {
                        points.add(new AnswerPresentation.ChartPoint(label, value));
                    }
                }
                if (!points.isEmpty()) {
                    series.add(new AnswerPresentation.ChartSeries(
                            clean(rawSeries.path("name").asText(null), "数值"), points));
                }
            }
            if (!series.isEmpty()) {
                charts.add(new AnswerPresentation.AnswerChart(
                        clean(chart.path("title").asText(null), "数据图表"), type, series));
            }
        }
        return List.copyOf(charts);
    }

    private BigDecimal decimal(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) return null;
        try {
            BigDecimal result = new BigDecimal(value.asText());
            return result.abs().compareTo(new BigDecimal("1E100")) > 0 ? null : result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AnswerPresentation.ChartType chartType(String value) {
        try {
            return AnswerPresentation.ChartType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private String safeMarkdown(String value) {
        return value.replaceAll("(?is)<\\s*(script|iframe|object|embed)[^>]*>.*?<\\s*/\\s*\\1\\s*>", "")
                .replaceAll("(?is)<\\s*/?\\s*(script|iframe|object|embed)[^>]*>", "")
                .replaceAll("(?i)javascript\\s*:", "");
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "\n\n内容较长，已截断。";
    }

    private ParsedPresentation fallback(String value) {
        String plainText = truncate(clean(value, "-"), 10_000);
        AnswerPresentation presentation = new AnswerPresentation(
                plainText, truncate(safeMarkdown(plainText), 10_000), List.of(), List.of());
        return new ParsedPresentation(presentation, toJson(presentation), true);
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null || fallback.isBlank() ? "-" : fallback) : value;
    }

    public record ParsedPresentation(AnswerPresentation presentation, String json, boolean fallback) {}
}
