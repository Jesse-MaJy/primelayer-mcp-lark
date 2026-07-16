package com.larkconnect.agent.agent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalExpressionResolverTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final TemporalExpressionResolver resolver = new TemporalExpressionResolver(
            Clock.fixed(Instant.parse("2026-07-14T06:00:00Z"), SHANGHAI));

    @Test
    void resolvesLastMonthWithCreateTimeForApplicationQuestion() {
        TemporalRange range = resolver.resolve("上个月罗诊项目申请了哪些作业票").orElseThrow();
        assertThat(range.startText()).isEqualTo("2026-06-01 00:00:00");
        assertThat(range.endText()).isEqualTo("2026-06-30 23:59:59");
        assertThat(range.filterField()).isEqualTo("createTime");
    }

    @Test
    void resolvesBusinessFieldAndCalendarExpressions() {
        assertThat(resolver.resolve("上周完成的流程").orElseThrow().filterField()).isEqualTo("processFinishTime");
        assertThat(resolver.resolve("本周进入当前节点的事项").orElseThrow().filterField()).isEqualTo("approvalArrivalTime");
        assertThat(resolver.resolve("近7天安全问题").orElseThrow().startText()).isEqualTo("2026-07-08 00:00:00");
        assertThat(resolver.resolve("2026-02-01至2026-02-28质量问题").orElseThrow().endText())
                .isEqualTo("2026-02-28 23:59:59");
    }

    @Test
    void handlesLeapYearAndYearBoundary() {
        TemporalExpressionResolver march = new TemporalExpressionResolver(
                Clock.fixed(Instant.parse("2024-03-01T03:00:00Z"), SHANGHAI));
        assertThat(march.resolve("上个月问题").orElseThrow().endText()).isEqualTo("2024-02-29 23:59:59");
        TemporalExpressionResolver january = new TemporalExpressionResolver(
                Clock.fixed(Instant.parse("2027-01-03T03:00:00Z"), SHANGHAI));
        assertThat(january.resolve("上月问题").orElseThrow().startText()).isEqualTo("2026-12-01 00:00:00");
    }
}
