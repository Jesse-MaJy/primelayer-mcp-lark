package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.mcp.McpQueryGateway.ToolObservation;

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

/** Produces bounded, deterministic full-record statistics for model consumption. */
final class ToolResultAggregator {
    private static final int MAX_IDENTIFIER_VALUES = 200;
    private static final int MAX_EVIDENCE_SAMPLES = 3;
    private static final int MAX_CATEGORIES = 50;
    private static final int MAX_SCALAR_TEXT = 200;
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");

    private final ObjectMapper objectMapper;

    ToolResultAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> aggregate(List<ToolObservation> observations) {
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<Object> evidence = new ArrayList<>();
        List<String> dataGaps = new ArrayList<>();
        Map<String, FieldStats> fields = new TreeMap<>();
        Map<String, LinkedHashSet<String>> identifiers = new TreeMap<>();
        Map<String, Integer> identifierTotals = new TreeMap<>();
        Set<String> uniqueRecords = new LinkedHashSet<>();
        int recordCount = 0;
        String earliest = null;
        String latest = null;

        for (ToolObservation observation : observations) {
            List<?> records = records(observation);
            int fetched = integer(observation.payload().get("fetchedCount"), records.size());
            Integer reported = nullableInteger(observation.payload().get("reportedTotalCount"));
            boolean complete = booleanValue(observation.payload().get("coverageComplete"),
                    reported == null || fetched >= reported);
            String reason = text(observation.payload().get("incompleteReason"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", observation.projectId());
            item.put("projectName", observation.projectName());
            item.put("toolName", observation.toolName());
            item.put("fetchedCount", fetched);
            item.put("reportedTotalCount", reported);
            item.put("pageCount", integer(observation.payload().get("pageCount"), observation.pages()));
            item.put("coverageComplete", complete);
            coverage.add(item);
            if (!complete) {
                dataGaps.add(observation.projectName() + "/" + observation.toolName() + " coverage "
                        + fetched + "/" + (reported == null ? "unknown" : reported)
                        + (reason == null ? "" : ": " + reason));
            }
            if (observation.error() != null && !observation.error().isBlank()) dataGaps.add(observation.error());

            for (Object rawRecord : records) {
                if (!(rawRecord instanceof Map<?, ?> rawMap)) continue;
                Map<String, Object> record = stringMap(rawMap);
                recordCount++;
                uniqueRecords.add(recordIdentity(record));
                if (evidence.size() < MAX_EVIDENCE_SAMPLES) evidence.add(sanitizeEvidence(record));
                Map<String, Object> scalars = new LinkedHashMap<>();
                flatten("", record, scalars, 0);
                for (Map.Entry<String, Object> entry : scalars.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    fields.computeIfAbsent(key, ignored -> new FieldStats()).accept(value);
                    if (isIdentifier(key) && value != null) {
                        identifierTotals.merge(key, 1, Integer::sum);
                        LinkedHashSet<String> values = identifiers.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                        if (values.size() < MAX_IDENTIFIER_VALUES) values.add(String.valueOf(value));
                    }
                    String date = date(value);
                    if (date != null) {
                        if (earliest == null || date.compareTo(earliest) < 0) earliest = date;
                        if (latest == null || date.compareTo(latest) > 0) latest = date;
                    }
                }
            }
        }

        Map<String, Object> fieldOutput = new LinkedHashMap<>();
        int finalRecordCount = recordCount;
        fields.forEach((name, stats) -> fieldOutput.put(name, stats.output(finalRecordCount)));
        Map<String, Object> identifierOutput = new LinkedHashMap<>();
        identifiers.forEach((name, values) -> {
            identifierOutput.put(name, List.copyOf(values));
            int omitted = Math.max(0, identifierTotals.getOrDefault(name, 0) - values.size());
            if (omitted > 0) identifierOutput.put(name + "OmittedCount", omitted);
        });
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("recordCount", recordCount);
        statistics.put("uniqueRecordCount", uniqueRecords.size());
        statistics.put("fields", fieldOutput);
        if (earliest != null) statistics.put("dateRange", Map.of("earliest", earliest, "latest", latest));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coverage", coverage);
        result.put("statistics", statistics);
        result.put("identifiers", identifierOutput);
        result.put("evidenceSamples", evidence);
        result.put("dataGaps", List.copyOf(new LinkedHashSet<>(dataGaps)));
        return result;
    }

    private List<?> records(ToolObservation observation) {
        Object value = observation.payload().get("records");
        return value instanceof List<?> list ? list : List.of();
    }

    private void flatten(String prefix, Map<String, Object> source, Map<String, Object> target, int depth) {
        if (depth > 2) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) flatten(key, stringMap(nested), target, depth + 1);
            else if (!(value instanceof List<?>)) target.put(key, value);
        }
    }

    private Map<String, Object> sanitizeEvidence(Map<String, Object> record) {
        Map<String, Object> safe = new LinkedHashMap<>();
        record.forEach((key, value) -> {
            if (value instanceof String string && string.length() > MAX_SCALAR_TEXT) {
                safe.put(key, "[omitted " + string.length() + " chars]");
            } else if (!(value instanceof List<?>) && !(value instanceof Map<?, ?>)) {
                safe.put(key, value);
            }
        });
        return safe;
    }

    private String recordIdentity(Map<String, Object> record) {
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (isIdentifier(entry.getKey()) && entry.getValue() != null) return entry.getKey() + "=" + entry.getValue();
        }
        try { return objectMapper.writeValueAsString(new TreeMap<>(record)); }
        catch (Exception ignored) { return String.valueOf(record); }
    }

    private boolean isIdentifier(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        return normalized.equals("id") || normalized.endsWith("id");
    }

    private String date(Object value) {
        if (!(value instanceof String string) || !ISO_DATE.matcher(string).matches()) return null;
        return string.substring(0, 10);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Integer nullableInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try { return Integer.parseInt(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private int integer(Object value, int fallback) {
        Integer parsed = nullableInteger(value);
        return parsed == null ? fallback : parsed;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class FieldStats {
        int present;
        int longTextCount;
        BigDecimal sum;
        BigDecimal min;
        BigDecimal max;
        final Map<String, Integer> categories = new LinkedHashMap<>();

        void accept(Object value) {
            if (value == null) return;
            present++;
            if (value instanceof Number number) {
                BigDecimal decimal = new BigDecimal(number.toString());
                sum = sum == null ? decimal : sum.add(decimal);
                min = min == null || decimal.compareTo(min) < 0 ? decimal : min;
                max = max == null || decimal.compareTo(max) > 0 ? decimal : max;
                return;
            }
            String text = String.valueOf(value);
            if (text.length() > MAX_SCALAR_TEXT) {
                longTextCount++;
                return;
            }
            categories.merge(text, 1, Integer::sum);
        }

        Map<String, Object> output(int recordCount) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nonNullCount", present);
            result.put("missingCount", Math.max(0, recordCount - present));
            if (sum != null) {
                result.put("sum", compact(sum));
                result.put("min", compact(min));
                result.put("max", compact(max));
                result.put("average", compact(sum.divide(BigDecimal.valueOf(present), 4, RoundingMode.HALF_UP)));
            } else {
                categories.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                        .limit(MAX_CATEGORIES)
                        .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
                int retained = result.entrySet().stream()
                        .filter(entry -> !"nonNullCount".equals(entry.getKey()) && !"missingCount".equals(entry.getKey()))
                        .mapToInt(entry -> (Integer) entry.getValue()).sum();
                int omitted = present - longTextCount - retained;
                if (omitted > 0) result.put("otherCategoryCount", omitted);
                if (longTextCount > 0) result.put("longTextCount", longTextCount);
            }
            return result;
        }

        private Number compact(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                long number = stripped.longValue();
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int) number;
                return number;
            }
            return stripped.doubleValue();
        }
    }
}
