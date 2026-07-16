package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerPresentationParserTest {
    private final AnswerPresentationParser parser = new AnswerPresentationParser(new ObjectMapper());

    @Test
    void parsesAndLimitsStructuredPresentation() {
        String rows = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> "{\"name\":\"item-" + i + "\",\"count\":\"" + i + "\"}")
                .reduce((a, b) -> a + "," + b).orElse("");
        String points = java.util.stream.IntStream.range(0, 55)
                .mapToObj(i -> "{\"label\":\"d" + i + "\",\"value\":" + i + "}")
                .reduce((a, b) -> a + "," + b).orElse("");
        String json = """
                {"plainText":"摘要","blocks":[
                  {"type":"markdown","content":"## 摘要"},
                  {"type":"table","title":"明细","totalRows":25,
                   "columns":[{"key":"name","label":"名称"},{"key":"count","label":"数量"}],
                   "rows":[%s]},
                  {"type":"chart","title":"趋势","chartType":"line",
                   "series":[{"name":"缺陷","points":[%s]}]}
                ]}
                """.formatted(rows, points);

        AnswerPresentationParser.ParsedPresentation parsed = parser.parse(json, "fallback");

        assertThat(parsed.presentation().plainText()).isEqualTo("摘要");
        assertThat(parsed.presentation().blocks()).extracting(AnswerPresentation.ContentBlock::type)
                .containsExactly(AnswerPresentation.BlockType.MARKDOWN,
                        AnswerPresentation.BlockType.TABLE, AnswerPresentation.BlockType.CHART);
        AnswerPresentation.AnswerTable table = parsed.presentation().blocks().get(1).table();
        AnswerPresentation.AnswerChart chart = parsed.presentation().blocks().get(2).chart();
        assertThat(table.rows()).hasSize(20);
        assertThat(table.totalRows()).isEqualTo(25);
        assertThat(chart.series().get(0).points()).hasSize(50);
        assertThat(chart.series().get(0).points().get(1).value())
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(parsed.json()).contains("\"plainText\":\"摘要\"").contains("\"blocks\"")
                .doesNotContain("\"tables\"", "\"charts\"", "\"markdown\":");
    }

    @Test
    void dropsInvalidChartsWithoutLosingMarkdown() {
        String json = """
                {"plainText":"结论","blocks":[
                  {"type":"markdown","content":"**结论**"},
                  {"type":"chart","title":"bad","chartType":"scatter","series":[]},
                  {"type":"chart","title":"empty","chartType":"bar","series":[]}
                ]}
                """;

        AnswerPresentationParser.ParsedPresentation parsed = parser.parse(json, "fallback");

        assertThat(parsed.presentation().blocks()).containsExactly(
                AnswerPresentation.ContentBlock.markdown("**结论**"));
    }

    @Test
    void keepsOnlyFirstFiveValidTableBlocks() {
        String blocks = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(this::tableBlock)
                .reduce((a, b) -> a + "," + b).orElse("");

        var parsed = parser.parse("{\"plainText\":\"摘要\",\"blocks\":[" + blocks + "]}", "fallback");

        assertThat(parsed.presentation().blocks())
                .filteredOn(block -> block.type() == AnswerPresentation.BlockType.TABLE)
                .hasSize(5)
                .extracting(block -> block.table().title())
                .containsExactly("表1", "表2", "表3", "表4", "表5");
    }

    @Test
    void invalidTableDoesNotConsumeValidTableLimit() {
        String validBlocks = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(this::tableBlock)
                .reduce((a, b) -> a + "," + b).orElse("");
        String json = "{\"plainText\":\"摘要\",\"blocks\":["
                + "{\"type\":\"table\",\"title\":\"无效表\",\"columns\":[],\"rows\":[]},"
                + validBlocks + "]}";

        var parsed = parser.parse(json, "fallback");

        assertThat(parsed.presentation().blocks())
                .filteredOn(block -> block.type() == AnswerPresentation.BlockType.TABLE)
                .hasSize(5)
                .extracting(block -> block.table().title())
                .containsExactly("表1", "表2", "表3", "表4", "表5");
    }

    @Test
    void keepsOnlyFirstFiveLegacyTables() {
        String tables = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(this::legacyTable)
                .reduce((a, b) -> a + "," + b).orElse("");

        var parsed = parser.parse("{\"plainText\":\"摘要\",\"markdown\":\"正文\",\"tables\":["
                + tables + "]}", "fallback");

        assertThat(parsed.presentation().blocks())
                .filteredOn(block -> block.type() == AnswerPresentation.BlockType.TABLE)
                .hasSize(5)
                .extracting(block -> block.table().title())
                .containsExactly("表1", "表2", "表3", "表4", "表5");
    }

    @Test
    void convertsLegacyFieldsToOrderedBlocksAndWritesOnlyNewProtocol() {
        String legacy = """
                {"plainText":"旧回答","markdown":"旧正文","tables":[{
                  "title":"旧表格","columns":[{"key":"name","label":"名称"}],"rows":[{"name":"A"}]
                }],"charts":[{
                  "title":"旧图表","type":"bar","series":[{"name":"数量","points":[{"label":"A","value":1}]}]
                }]}
                """;

        AnswerPresentationParser.ParsedPresentation parsed = parser.parse(legacy, "fallback");

        assertThat(parsed.presentation().blocks()).extracting(AnswerPresentation.ContentBlock::type)
                .containsExactly(AnswerPresentation.BlockType.MARKDOWN,
                        AnswerPresentation.BlockType.TABLE, AnswerPresentation.BlockType.CHART);
        assertThat(parsed.json()).contains("\"blocks\"").doesNotContain("\"tables\"", "\"charts\"");
    }

    @Test
    void fallsBackForEmptyOrMalformedJson() {
        assertThat(parser.parse("", "原始回答").presentation())
                .isEqualTo(AnswerPresentation.markdownOnly("原始回答"));
        assertThat(parser.parse("not-json", "原始回答").presentation())
                .isEqualTo(AnswerPresentation.markdownOnly("原始回答"));
    }

    private String tableBlock(int index) {
        return "{\"type\":\"table\",\"title\":\"表" + index
                + "\",\"columns\":[{\"key\":\"name\",\"label\":\"名称\"}],"
                + "\"rows\":[{\"name\":\"A\"}]}";
    }

    private String legacyTable(int index) {
        return "{\"title\":\"表" + index
                + "\",\"columns\":[{\"key\":\"name\",\"label\":\"名称\"}],"
                + "\"rows\":[{\"name\":\"A\"}]}";
    }
}
