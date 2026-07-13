package com.larkconnect.agent.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AnswerPresentation(
        String plainText,
        String markdown,
        List<AnswerTable> tables,
        List<AnswerChart> charts
) {
    public AnswerPresentation {
        plainText = text(plainText);
        markdown = text(markdown);
        tables = tables == null ? List.of() : List.copyOf(tables);
        charts = charts == null ? List.of() : List.copyOf(charts);
    }

    public static AnswerPresentation markdownOnly(String value) {
        String safe = text(value);
        return new AnswerPresentation(safe, safe, List.of(), List.of());
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public record AnswerTable(String title, List<TableColumn> columns,
                              List<Map<String, String>> rows, int totalRows) {
        public AnswerTable {
            title = text(title);
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : rows.stream().map(Map::copyOf).toList();
            totalRows = Math.max(totalRows, rows.size());
        }
    }

    public record TableColumn(String key, String label) {}

    public record AnswerChart(String title, ChartType type, List<ChartSeries> series) {
        public AnswerChart {
            title = text(title);
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    public enum ChartType { BAR, LINE, PIE }

    public record ChartSeries(String name, List<ChartPoint> points) {
        public ChartSeries {
            name = text(name);
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record ChartPoint(String label, BigDecimal value) {}
}
