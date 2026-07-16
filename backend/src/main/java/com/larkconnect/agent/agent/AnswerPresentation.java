package com.larkconnect.agent.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AnswerPresentation(
        String plainText,
        List<ContentBlock> blocks
) {
    public AnswerPresentation {
        plainText = text(plainText);
        blocks = blocks == null ? List.of() : blocks.stream().filter(java.util.Objects::nonNull).toList();
    }

    public static AnswerPresentation markdownOnly(String value) {
        String safe = text(value);
        return new AnswerPresentation(safe, List.of(ContentBlock.markdown(safe)));
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

    public enum BlockType { MARKDOWN, TABLE, CHART }

    public record ContentBlock(BlockType type, String markdown, AnswerTable table, AnswerChart chart) {
        public ContentBlock {
            if (type == null) throw new IllegalArgumentException("内容块类型不能为空");
            switch (type) {
                case MARKDOWN -> {
                    markdown = text(markdown);
                    table = null;
                    chart = null;
                }
                case TABLE -> {
                    if (table == null) throw new IllegalArgumentException("表格内容块缺少表格数据");
                    markdown = null;
                    chart = null;
                }
                case CHART -> {
                    if (chart == null) throw new IllegalArgumentException("图表内容块缺少图表数据");
                    markdown = null;
                    table = null;
                }
            }
        }

        public static ContentBlock markdown(String value) {
            return new ContentBlock(BlockType.MARKDOWN, value, null, null);
        }

        public static ContentBlock table(AnswerTable value) {
            return new ContentBlock(BlockType.TABLE, null, value, null);
        }

        public static ContentBlock chart(AnswerChart value) {
            return new ContentBlock(BlockType.CHART, null, null, value);
        }
    }

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
