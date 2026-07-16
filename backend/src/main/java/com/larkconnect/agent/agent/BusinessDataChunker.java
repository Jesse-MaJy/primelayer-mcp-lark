package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.mcp.McpQueryGateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts all filtered MCP business records into bounded, secret-free model chunks. */
final class BusinessDataChunker {
    static final int DEFAULT_MAX_CHUNK_CHARS = 20_000;
    private static final Set<String> INTERNAL_FIELDS = Set.of(
            "dataid", "formid", "approvalid", "fieldtype", "processid",
            "mcptoken", "mcptokenciphertext", "authorization", "accesstoken", "refreshtoken");

    private final ObjectMapper mapper;
    private final int maxChars;

    BusinessDataChunker(ObjectMapper mapper) {
        this(mapper, DEFAULT_MAX_CHUNK_CHARS);
    }

    BusinessDataChunker(ObjectMapper mapper, int maxChars) {
        this.mapper = mapper;
        this.maxChars = Math.max(2_000, maxChars);
    }

    ChunkedData chunk(List<McpQueryGateway.ToolObservation> observations) {
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (McpQueryGateway.ToolObservation observation : observations) {
            Object raw = observation.payload().get("records");
            if (!(raw instanceof List<?> list)) continue;
            for (Object record : list) {
                Object sanitized = sanitize(record, 0);
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("projectId", observation.projectId());
                envelope.put("projectName", observation.projectName());
                envelope.put("recordFingerprint", fingerprint(write(sanitized)));
                envelope.put("record", sanitized);
                records.add(envelope);
            }
            Object reason = observation.payload().get("incompleteReason");
            if (reason != null && !String.valueOf(reason).isBlank()) gaps.add(String.valueOf(reason));
            if (observation.error() != null && !observation.error().isBlank()) gaps.add(observation.error());
        }

        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int currentChars = 2;
        for (Map<String, Object> record : records) {
            String json = write(record);
            if (json.length() > maxChars - 500) {
                if (!current.isEmpty()) {
                    groups.add(List.copyOf(current));
                    current.clear();
                    currentChars = 2;
                }
                groups.addAll(fragment(record));
                continue;
            }
            if (!current.isEmpty() && currentChars + json.length() + 1 > maxChars - 300) {
                groups.add(List.copyOf(current));
                current.clear();
                currentChars = 2;
            }
            current.add(record);
            currentChars += json.length() + 1;
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        if (groups.isEmpty()) groups.add(List.of());

        List<DataChunk> chunks = new ArrayList<>();
        int count = groups.size();
        for (int i = 0; i < count; i++) {
            List<Map<String, Object>> group = groups.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chunkIndex", i + 1);
            payload.put("chunkCount", count);
            payload.put("records", group);
            payload.put("recordEntries", group.size());
            String json = write(payload);
            chunks.add(new DataChunk(i + 1, count, json, group.size(), json.length(),
                    group.stream().map(item -> String.valueOf(item.get("recordFingerprint"))).distinct().toList()));
        }
        return new ChunkedData(List.copyOf(chunks), records.size(), List.copyOf(gaps));
    }

    private List<List<Map<String, Object>>> fragment(Map<String, Object> record) {
        int size = Math.max(1_000, maxChars - 1_500);
        List<FieldFragment> fields = new ArrayList<>();
        flatten(record.get("record"), "$", size, fields);
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        for (FieldFragment field : fields) {
            Map<String, Object> fragment = new LinkedHashMap<>();
            fragment.put("projectId", record.get("projectId"));
            fragment.put("projectName", record.get("projectName"));
            fragment.put("recordFingerprint", record.get("recordFingerprint"));
            fragment.put("fieldPath", field.path());
            fragment.put("fieldFragmentIndex", field.index());
            fragment.put("fieldFragmentCount", field.count());
            fragment.put("fieldValueFragment", field.value());
            groups.add(List.of(fragment));
        }
        return groups;
    }

    private void flatten(Object value, String path, int fragmentSize, List<FieldFragment> target) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            map.forEach((key, nested) -> flatten(nested, path + "." + key, fragmentSize, target));
            return;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            for (int index = 0; index < list.size(); index++) {
                flatten(list.get(index), path + "[" + index + "]", fragmentSize, target);
            }
            return;
        }
        String serialized = value instanceof String text ? text : write(value);
        int count = Math.max(1, (serialized.length() + fragmentSize - 1) / fragmentSize);
        for (int index = 0; index < count; index++) {
            int start = index * fragmentSize;
            int end = Math.min(serialized.length(), start + fragmentSize);
            target.add(new FieldFragment(path, index + 1, count, serialized.substring(start, end)));
        }
    }

    private Object sanitize(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (depth > 12) return "[nested value omitted]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (!INTERNAL_FIELDS.contains(normalized) && !normalized.endsWith("token")
                        && !normalized.contains("authorization")) {
                    result.put(name, sanitize(nested, depth + 1));
                }
            });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(sanitize(item, depth + 1)));
            return result;
        }
        return String.valueOf(value);
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("业务数据无法序列化", e); }
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (Exception ignored) {
            return Integer.toUnsignedString(value.hashCode());
        }
    }

    record DataChunk(int index, int count, String json, int recordEntries, int chars,
                     List<String> recordFingerprints) {}
    record ChunkedData(List<DataChunk> chunks, int recordCount, List<String> dataGaps) {}
    private record FieldFragment(String path, int index, int count, String value) {}
}
