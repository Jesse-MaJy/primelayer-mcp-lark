package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Bounded incremental statistics suitable for checkpoint persistence. */
public final class StreamingStatistics {
    private static final int CATEGORY_THRESHOLD = 1_000;
    private static final int TOP_CATEGORIES = 100;
    private static final int UNIQUE_HASH_LIMIT = 20_000;
    private static final int EVIDENCE_LIMIT = 3;
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");
    private final ObjectMapper mapper;
    private final Map<String, Field> fields = new TreeMap<>();
    private final Set<String> uniqueHashes = new LinkedHashSet<>();
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private long fetchedCount;
    private Long reportedTotalCount;
    private boolean uniqueApproximate;
    private String earliest;
    private String latest;

    public StreamingStatistics(ObjectMapper mapper) { this.mapper = mapper; }

    public void accept(List<? extends Map<String, Object>> records, Number reportedTotal) {
        if (reportedTotal != null) reportedTotalCount = reportedTotal.longValue();
        for (Map<String, Object> record : records) {
            fetchedCount++;
            if (evidence.size() < EVIDENCE_LIMIT) evidence.add(sanitize(record));
            if (uniqueHashes.size() < UNIQUE_HASH_LIMIT) uniqueHashes.add(identity(record));
            else uniqueApproximate = true;
            flatten("", record, 0);
        }
    }

    public int retainedRecordCount() { return evidence.size(); }
    public long fetchedCount() { return fetchedCount; }
    public Long reportedTotalCount() { return reportedTotalCount; }

    /** Serializable bounded state used between pagination batches. */
    public Map<String, Object> state() {
        Map<String, Object> fieldState = new LinkedHashMap<>();
        fields.forEach((name, field) -> fieldState.put(name, field.state()));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("fetchedCount", fetchedCount);
        state.put("reportedTotalCount", reportedTotalCount);
        state.put("uniqueHashes", List.copyOf(uniqueHashes));
        state.put("uniqueApproximate", uniqueApproximate);
        state.put("evidence", List.copyOf(evidence));
        state.put("earliest", earliest);
        state.put("latest", latest);
        state.put("fields", fieldState);
        return state;
    }

    @SuppressWarnings("unchecked")
    public static StreamingStatistics restore(ObjectMapper mapper, Map<String, Object> state) {
        StreamingStatistics restored = new StreamingStatistics(mapper);
        if (state == null || state.isEmpty()) return restored;
        restored.fetchedCount = longValue(state.get("fetchedCount"));
        Object reported = state.get("reportedTotalCount");
        if (reported != null) restored.reportedTotalCount = longValue(reported);
        if (state.get("uniqueHashes") instanceof List<?> hashes) {
            hashes.stream().limit(UNIQUE_HASH_LIMIT).map(String::valueOf).forEach(restored.uniqueHashes::add);
        }
        restored.uniqueApproximate = Boolean.TRUE.equals(state.get("uniqueApproximate"));
        if (state.get("evidence") instanceof List<?> samples) {
            for (Object sample : samples) {
                if (restored.evidence.size() >= EVIDENCE_LIMIT) break;
                if (sample instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    restored.evidence.add(copy);
                }
            }
        }
        restored.earliest = text(state.get("earliest"));
        restored.latest = text(state.get("latest"));
        if (state.get("fields") instanceof Map<?, ?> rawFields) {
            rawFields.forEach((name, raw) -> {
                if (raw instanceof Map<?, ?> map) restored.fields.put(String.valueOf(name),
                        Field.restore((Map<String, Object>) map));
            });
        }
        return restored;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> outputFields = new LinkedHashMap<>();
        fields.forEach((name, field) -> outputFields.put(name, field.output(fetchedCount)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fetchedCount", fetchedCount);
        result.put("reportedTotalCount", reportedTotalCount);
        result.put("coverageComplete", reportedTotalCount != null && fetchedCount >= reportedTotalCount);
        result.put("coveragePercent", reportedTotalCount == null || reportedTotalCount == 0 ? null
                : Math.min(100d, fetchedCount * 100d / reportedTotalCount));
        result.put("uniqueRecordCount", uniqueHashes.size());
        result.put("uniqueRecordCountApproximate", uniqueApproximate);
        result.put("fields", outputFields);
        result.put("evidenceSamples", List.copyOf(evidence));
        if (earliest != null) result.put("dateRange", Map.of("earliest", earliest, "latest", latest));
        return result;
    }

    private void flatten(String prefix, Map<String, Object> source, int depth) {
        if (depth > 2) return;
        source.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> mapped = new LinkedHashMap<>();
                nested.forEach((k, v) -> mapped.put(String.valueOf(k), v));
                flatten(path, mapped, depth + 1);
            } else if (!(value instanceof Iterable<?>)) {
                fields.computeIfAbsent(path, ignored -> new Field()).accept(value);
                if (value instanceof String string && DATE.matcher(string).matches()) {
                    String date = string.substring(0, 10);
                    earliest = earliest == null || date.compareTo(earliest) < 0 ? date : earliest;
                    latest = latest == null || date.compareTo(latest) > 0 ? date : latest;
                }
            }
        });
    }

    private String identity(Map<String, Object> record) {
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            if ((key.equals("id") || key.endsWith("id")) && entry.getValue() != null) return key + "=" + entry.getValue();
        }
        try { return mapper.writeValueAsString(new TreeMap<>(record)); }
        catch (Exception ignored) { return String.valueOf(record.hashCode()); }
    }

    private Map<String, Object> sanitize(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (value instanceof String text) result.put(key, text.length() > 200 ? "[omitted " + text.length() + " chars]" : text);
            else if (!(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>)) result.put(key, value);
        });
        return result;
    }

    private static final class Field {
        long present;
        BigDecimal sum, min, max;
        final Map<String, Long> categories = new LinkedHashMap<>();
        boolean approximate;

        void accept(Object value) {
            if (value == null) return;
            present++;
            if (value instanceof Number number) {
                BigDecimal n = new BigDecimal(number.toString());
                sum = sum == null ? n : sum.add(n);
                min = min == null || n.compareTo(min) < 0 ? n : min;
                max = max == null || n.compareTo(max) > 0 ? n : max;
                return;
            }
            String key = String.valueOf(value);
            if (!approximate && categories.size() >= CATEGORY_THRESHOLD && !categories.containsKey(key)) {
                approximate = true;
                trim();
            }
            if (!approximate || categories.containsKey(key) || categories.size() < TOP_CATEGORIES) categories.merge(key, 1L, Long::sum);
        }

        Map<String, Object> output(long total) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nonNullCount", present);
            result.put("missingCount", Math.max(0, total - present));
            if (sum != null) {
                result.put("sum", compact(sum)); result.put("min", compact(min)); result.put("max", compact(max));
                result.put("average", compact(sum.divide(BigDecimal.valueOf(present), 4, RoundingMode.HALF_UP)));
            } else {
                Map<String, Long> top = new LinkedHashMap<>();
                categories.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(TOP_CATEGORIES).forEach(e -> top.put(e.getKey(), e.getValue()));
                result.put("topCategories", top);
                result.put("approximate", approximate);
            }
            return result;
        }

        Map<String, Object> state() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("present", present);
            result.put("sum", decimal(sum));
            result.put("min", decimal(min));
            result.put("max", decimal(max));
            result.put("categories", new LinkedHashMap<>(categories));
            result.put("approximate", approximate);
            return result;
        }

        static Field restore(Map<String, Object> state) {
            Field field = new Field();
            field.present = longValue(state.get("present"));
            field.sum = number(state.get("sum"));
            field.min = number(state.get("min"));
            field.max = number(state.get("max"));
            field.approximate = Boolean.TRUE.equals(state.get("approximate"));
            if (state.get("categories") instanceof Map<?, ?> values) {
                values.forEach((key, value) -> field.categories.put(String.valueOf(key), longValue(value)));
            }
            return field;
        }

        void trim() {
            Map<String, Long> top = new LinkedHashMap<>();
            categories.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .limit(TOP_CATEGORIES).forEach(e -> top.put(e.getKey(), e.getValue()));
            categories.clear(); categories.putAll(top);
        }

        Number compact(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.scale() <= 0 ? stripped.longValue() : stripped.doubleValue();
        }

        private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
        private static BigDecimal number(Object value) {
            return value == null || String.valueOf(value).isBlank() ? null : new BigDecimal(String.valueOf(value));
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))
                ? null : String.valueOf(value);
    }
}
