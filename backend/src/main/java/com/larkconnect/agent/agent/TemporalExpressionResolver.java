package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.BusinessTimeConfig;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves supported Chinese date expressions deterministically in the business timezone. */
final class TemporalExpressionResolver {
    private static final Pattern RECENT_DAYS = Pattern.compile("近\\s*(\\d{1,3})\\s*天");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern CN_DATE = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern ISO_RANGE = Pattern.compile(
            "(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})\\s*(?:到|至|~|—|-)\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern CN_RANGE = Pattern.compile(
            "(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日\\s*(?:到|至|~|—|-)\\s*(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日");

    private final Clock clock;

    TemporalExpressionResolver(Clock clock) {
        this.clock = clock;
    }

    Optional<TemporalRange> resolve(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        LocalDate today = LocalDate.now(clock.withZone(BusinessTimeConfig.BUSINESS_ZONE));
        String field = filterField(question);

        Matcher isoRange = ISO_RANGE.matcher(question);
        if (isoRange.find()) {
            LocalDate start = date(isoRange, 1, 2, 3, today.getYear());
            LocalDate end = date(isoRange, 4, 5, 6, today.getYear());
            return Optional.of(range(start, end, field, start + " 至 " + end));
        }
        Matcher cnRange = CN_RANGE.matcher(question);
        if (cnRange.find()) {
            int startYear = number(cnRange.group(1), today.getYear());
            int endYear = number(cnRange.group(4), startYear);
            LocalDate start = LocalDate.of(startYear, number(cnRange.group(2), 1), number(cnRange.group(3), 1));
            LocalDate end = LocalDate.of(endYear, number(cnRange.group(5), 1), number(cnRange.group(6), 1));
            return Optional.of(range(start, end, field, start + " 至 " + end));
        }
        Matcher recent = RECENT_DAYS.matcher(question);
        if (recent.find()) {
            int days = Integer.parseInt(recent.group(1));
            if (days < 1 || days > 366) throw new IllegalArgumentException("近 N 天仅支持 1 至 366 天");
            return Optional.of(range(today.minusDays(days - 1L), today, field, "近" + days + "天"));
        }
        if (question.contains("上个月") || question.contains("上月")) {
            YearMonth month = YearMonth.from(today).minusMonths(1);
            return Optional.of(range(month.atDay(1), month.atEndOfMonth(), field, "上个月"));
        }
        if (question.contains("本月") || question.contains("这个月")) {
            return Optional.of(range(today.withDayOfMonth(1), today, field, "本月"));
        }
        if (question.contains("上周")) {
            LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return Optional.of(range(thisMonday.minusWeeks(1), thisMonday.minusDays(1), field, "上周"));
        }
        if (question.contains("本周") || question.contains("这周")) {
            return Optional.of(range(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today, field, "本周"));
        }
        if (question.contains("昨天")) return Optional.of(range(today.minusDays(1), today.minusDays(1), field, "昨天"));
        if (question.contains("今天") || question.contains("今日")) return Optional.of(range(today, today, field, "今天"));

        Matcher isoDate = ISO_DATE.matcher(question);
        if (isoDate.find()) {
            LocalDate date = date(isoDate, 1, 2, 3, today.getYear());
            return Optional.of(range(date, date, field, date.toString()));
        }
        Matcher cnDate = CN_DATE.matcher(question);
        if (cnDate.find()) {
            LocalDate date = LocalDate.of(number(cnDate.group(1), today.getYear()),
                    number(cnDate.group(2), 1), number(cnDate.group(3), 1));
            return Optional.of(range(date, date, field, date.toString()));
        }
        return Optional.empty();
    }

    private TemporalRange range(LocalDate start, LocalDate end, String field, String label) {
        return new TemporalRange(start.atStartOfDay(), end.atTime(23, 59, 59), field, label,
                BusinessTimeConfig.BUSINESS_ZONE);
    }

    private LocalDate date(Matcher matcher, int year, int month, int day, int fallbackYear) {
        return LocalDate.of(number(matcher.group(year), fallbackYear),
                number(matcher.group(month), 1), number(matcher.group(day), 1));
    }

    private int number(String value, int fallback) {
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private String filterField(String question) {
        if (containsAny(question, "节点开始", "进入当前节点", "到达节点", "节点到达")) return "approvalArrivalTime";
        if (containsAny(question, "审批完成", "流程完成", "完成", "结束", "办结")) return "processFinishTime";
        return "createTime";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }
}
