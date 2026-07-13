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
                {"plainText":"摘要","markdown":"## 摘要","tables":[{
                  "title":"明细","totalRows":25,
                  "columns":[{"key":"name","label":"名称"},{"key":"count","label":"数量"}],
                  "rows":[%s]
                }],"charts":[{
                  "title":"趋势","type":"line","series":[{"name":"缺陷","points":[%s]}]
                }]}
                """.formatted(rows, points);

        AnswerPresentationParser.ParsedPresentation parsed = parser.parse(json, "fallback");

        assertThat(parsed.presentation().plainText()).isEqualTo("摘要");
        assertThat(parsed.presentation().tables().get(0).rows()).hasSize(20);
        assertThat(parsed.presentation().tables().get(0).totalRows()).isEqualTo(25);
        assertThat(parsed.presentation().charts().get(0).series().get(0).points()).hasSize(50);
        assertThat(parsed.presentation().charts().get(0).series().get(0).points().get(1).value())
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(parsed.json()).contains("\"plainText\":\"摘要\"");
    }

    @Test
    void dropsInvalidChartsWithoutLosingMarkdown() {
        String json = """
                {"plainText":"结论","markdown":"**结论**","tables":[],"charts":[
                  {"title":"bad","type":"scatter","series":[]},
                  {"title":"empty","type":"bar","series":[]}
                ]}
                """;

        AnswerPresentationParser.ParsedPresentation parsed = parser.parse(json, "fallback");

        assertThat(parsed.presentation().markdown()).isEqualTo("**结论**");
        assertThat(parsed.presentation().charts()).isEmpty();
    }

    @Test
    void fallsBackForEmptyOrMalformedJson() {
        assertThat(parser.parse("", "原始回答").presentation())
                .isEqualTo(AnswerPresentation.markdownOnly("原始回答"));
        assertThat(parser.parse("not-json", "原始回答").presentation())
                .isEqualTo(AnswerPresentation.markdownOnly("原始回答"));
    }
}
