package com.larkconnect.agent.agent;

import com.larkconnect.agent.mcp.McpQueryGateway;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Applies a second, local date boundary so an MCP server cannot leak out-of-range records into analysis. */
final class TemporalRecordFilter {
    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE);

    List<McpQueryGateway.ToolObservation> apply(List<McpQueryGateway.ToolObservation> observations,
                                                TemporalRange range) {
        if (range == null || observations == null) return observations;
        return observations.stream().map(observation -> filter(observation, range)).toList();
    }

    private McpQueryGateway.ToolObservation filter(McpQueryGateway.ToolObservation observation,
                                                    TemporalRange range) {
        Object rawRecords = observation.payload().get("records");
        if (!(rawRecords instanceof List<?> records)) return observation;
        List<Object> accepted = new ArrayList<>();
        int missing = 0;
        int outside = 0;
        for (Object record : records) {
            Object rawDate = findField(record, range.filterField(), 0);
            LocalDateTime value = parse(rawDate, range.zone());
            if (value == null) {
                missing++;
                continue;
            }
            if (value.isBefore(range.start()) || value.isAfter(range.end())) outside++;
            else accepted.add(record);
        }
        Map<String, Object> payload = new LinkedHashMap<>(observation.payload());
        payload.put("records", List.copyOf(accepted));
        payload.put("dateFilter", Map.of(
                "field", range.filterField(),
                "start", range.startText(),
                "end", range.endText(),
                "sourceRecordCount", records.size(),
                "acceptedRecordCount", accepted.size(),
                "outOfRangeCount", outside,
                "missingDateCount", missing));
        payload.put("fetchedCount", accepted.size());
        payload.put("reportedTotalCount", accepted.size());
        boolean upstreamComplete = !Boolean.FALSE.equals(observation.payload().get("coverageComplete"));
        boolean verified = upstreamComplete && missing == 0 && outside == 0;
        payload.put("coverageComplete", verified);
        if (!verified) {
            List<String> reasons = new ArrayList<>();
            if (!upstreamComplete) reasons.add("MCP 分页未完整结束");
            if (outside > 0) reasons.add("服务端返回 " + outside + " 条范围外记录，已在本地剔除");
            if (missing > 0) reasons.add(missing + " 条记录缺少 " + range.filterField() + "，未进入分析");
            payload.put("incompleteReason", String.join("；", reasons));
        }
        return new McpQueryGateway.ToolObservation(observation.projectId(), observation.projectName(),
                observation.toolName(), observation.status(),
                Collections.unmodifiableMap(new LinkedHashMap<>(payload)), observation.error(),
                observation.physicalCalls(), observation.pages(), observation.truncated(),
                accepted.size(), accepted.size(), observation.traceEventIds());
    }

    private Object findField(Object value, String field, int depth) {
        if (value == null || depth > 5) return null;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (field.equals(String.valueOf(entry.getKey()))) return unwrap(entry.getValue());
            }
            for (Object nested : map.values()) {
                Object found = findField(nested, field, depth + 1);
                if (found != null) return found;
            }
        }
        if (value instanceof List<?> list) {
            for (Object nested : list) {
                Object found = findField(nested, field, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Object unwrap(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("value", "text", "date", "dateTime")) {
                if (map.containsKey(key)) return map.get(key);
            }
        }
        if (value instanceof List<?> list && list.size() == 1) return unwrap(list.get(0));
        return value;
    }

    private LocalDateTime parse(Object raw, ZoneId zone) {
        if (raw == null) return null;
        if (raw instanceof Number number) {
            long epoch = number.longValue();
            if (String.valueOf(Math.abs(epoch)).length() <= 10) epoch *= 1000;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), zone);
        }
        String value = String.valueOf(raw).trim();
        if (value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDateTime(); }
        catch (DateTimeParseException ignored) { }
        for (DateTimeFormatter formatter : LOCAL_FORMATS) {
            try {
                if (formatter == DateTimeFormatter.ISO_LOCAL_DATE) {
                    return LocalDate.parse(value, formatter).atStartOfDay();
                }
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) { }
        }
        return null;
    }
}
