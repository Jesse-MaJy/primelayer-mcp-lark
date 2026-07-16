package com.larkconnect.agent.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** A business-time range resolved before any MCP planning occurs. */
public record TemporalRange(
        LocalDateTime start,
        LocalDateTime end,
        String filterField,
        String label,
        ZoneId zone
) {
    private static final DateTimeFormatter MCP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public TemporalRange {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("时间范围无效");
        }
        if (filterField == null || filterField.isBlank()) filterField = "createTime";
        if (label == null || label.isBlank()) label = start.toLocalDate() + " 至 " + end.toLocalDate();
        if (zone == null) throw new IllegalArgumentException("业务时区不能为空");
    }

    public String startText() { return start.format(MCP_TIME); }
    public String endText() { return end.format(MCP_TIME); }
    public LocalDate startDate() { return start.toLocalDate(); }
    public LocalDate endDate() { return end.toLocalDate(); }
    public boolean singleDay() { return startDate().equals(endDate()); }

    public String instruction() {
        String resolved = singleDay()
                ? "用户问题中“" + label + "”的目标日期：" + startDate() + "。"
                : "用户时间范围“" + label + "”：" + startText() + " 至 " + endText() + "。";
        return resolved
                + "（" + zone + "），业务时间字段为 filter." + filterField + "。"
                + "所有表单数据查询必须携带该范围，最终回答必须标注该时间口径；"
                + "不得用范围外记录替代。";
    }
}
