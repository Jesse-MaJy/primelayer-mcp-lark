package com.larkconnect.agent.admin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuDemoCardCatalogTest {
    private final FeishuDemoCardCatalog catalog = new FeishuDemoCardCatalog();

    @Test
    void returnsSixUniqueJson2PresetsWithChartsAndTables() {
        List<FeishuDemoCardCatalog.CardPreset> presets = catalog.presets();

        assertThat(presets).hasSize(6);
        assertThat(presets).extracting(FeishuDemoCardCatalog.CardPreset::key).doesNotHaveDuplicates();
        for (FeishuDemoCardCatalog.CardPreset preset : presets) {
            assertThat(preset.card()).containsEntry("schema", "2.0").containsKey("body");
            assertThat(preset.card()).doesNotContainKey("elements");
            assertThat(elements(preset.card())).anyMatch(element -> "chart".equals(element.get("tag")));
            assertThat(elements(preset.card())).anyMatch(element -> "table".equals(element.get("tag")));
        }
    }

    @Test
    void usesOnlyJson2ComponentsAndWhitelistedChartTypes() {
        Set<String> chartTypes = new LinkedHashSet<>();

        for (FeishuDemoCardCatalog.CardPreset preset : catalog.presets()) {
            String jsonShape = preset.card().toString();
            assertThat(jsonShape).doesNotContain("lark_md", "tag=action", "tag=note", "echarts", "javascript");
            for (Map<String, Object> chart : charts(preset.card())) {
                chartTypes.add(String.valueOf(spec(chart).get("type")));
                assertThat(spec(chart).get("type")).isIn("bar", "line", "pie");
            }
            assertThat(charts(preset.card())).hasSizeLessThanOrEqualTo(3);
        }

        assertThat(chartTypes).containsExactlyInAnyOrder("bar", "line", "pie");
        assertThat(charts(find("basic-test").card()))
                .extracting(chart -> String.valueOf(spec(chart).get("type")))
                .containsExactlyInAnyOrder("bar", "line", "pie");
    }

    @Test
    void includesSevenDayRocheTrendAndThreeProjectComparisonData() {
        Map<String, Object> answer = find("primelayer-answer").card();
        Map<String, Object> trend = charts(answer).stream()
                .filter(chart -> "line".equals(spec(chart).get("type")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> trendValues = values(trend);

        assertThat(trendValues.stream().map(row -> row.get("label")).collect(java.util.stream.Collectors.toSet()))
                .hasSize(7);
        assertThat(trendValues.stream().map(row -> row.get("series")).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("计划完成率", "实际完成率");

        String weekly = find("weekly-summary").card().toString();
        assertThat(weekly).contains("Roche", "Siemens", "XDL");
    }

    @Test
    void providesRichDemoTablesWithFiveToTwelveRows() {
        for (FeishuDemoCardCatalog.CardPreset preset : catalog.presets()) {
            for (Map<String, Object> table : tables(preset.card())) {
                assertThat((List<?>) table.get("rows")).hasSizeBetween(5, 12);
            }
        }
    }

    private FeishuDemoCardCatalog.CardPreset find(String key) {
        return catalog.presets().stream().filter(preset -> key.equals(preset.key())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> elements(Map<String, Object> card) {
        return (List<Map<String, Object>>) ((Map<String, Object>) card.get("body")).get("elements");
    }

    private List<Map<String, Object>> charts(Map<String, Object> card) {
        return elements(card).stream().filter(element -> "chart".equals(element.get("tag"))).toList();
    }

    private List<Map<String, Object>> tables(Map<String, Object> card) {
        return elements(card).stream().filter(element -> "table".equals(element.get("tag"))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("chart_spec");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> values(Map<String, Object> chart) {
        Object data = spec(chart).get("data");
        if (data instanceof Map<?, ?> dataMap) {
            return (List<Map<String, Object>>) dataMap.get("values");
        }
        return new ArrayList<>();
    }
}
