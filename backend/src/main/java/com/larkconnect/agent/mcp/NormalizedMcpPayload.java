package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalized business data extracted from either direct JSON or MCP text content blocks. */
record NormalizedMcpPayload(List<Object> items, Integer reportedTotalCount,
                            Boolean hasMore, String nextCursor, Object businessPayload) {

    static NormalizedMcpPayload from(ObjectMapper mapper, Map<String, Object> response) {
        List<Object> candidates = new ArrayList<>();
        Object result = response.getOrDefault("result", response);
        candidates.add(result);
        collectTextPayloads(mapper, result, candidates);

        List<Object> items = null;
        Integer total = null;
        Boolean hasMore = null;
        String nextCursor = null;
        Object selected = result;
        for (Object candidate : candidates) {
            if (items == null) {
                List<Object> found = findItems(candidate);
                if (found != null) {
                    items = found;
                    selected = candidate;
                }
            }
            if (total == null) total = findInteger(candidate, "totalCount", "total_count", "total", "count");
            if (hasMore == null) hasMore = findBoolean(candidate, "hasMore", "has_more", "more");
            if (nextCursor == null) nextCursor = findString(candidate, "nextCursor", "next_cursor", "nextPageToken", "next_page_token");
        }
        return new NormalizedMcpPayload(items == null ? List.of() : List.copyOf(items),
                total, hasMore, nextCursor, selected);
    }

    @SuppressWarnings("unchecked")
    private static void collectTextPayloads(ObjectMapper mapper, Object value, List<Object> candidates) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            Object content = map.get("content");
            if (content instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> rawBlock)) continue;
                    Object text = rawBlock.get("text");
                    if (text instanceof String string) {
                        Object parsed = parseJson(mapper, string);
                        if (parsed != null) candidates.add(parsed);
                    }
                }
            }
            for (Object nested : map.values()) collectTextPayloads(mapper, nested, candidates);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) collectTextPayloads(mapper, nested, candidates);
        }
    }

    private static Object parseJson(ObjectMapper mapper, String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) text = text.substring(firstLine + 1, closing).trim();
        }
        if (!(text.startsWith("{") || text.startsWith("["))) return null;
        try {
            return mapper.readValue(text, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> findItems(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (String key : List.of("items", "records", "list", "forms", "resources")) {
                Object candidate = map.get(key);
                if (candidate instanceof List<?> list) return new ArrayList<>((List<Object>) list);
            }
            Object data = map.get("data");
            if (data instanceof List<?> list) return new ArrayList<>((List<Object>) list);
            if (data != null) {
                List<Object> nested = findItems(data);
                if (nested != null) return nested;
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if ("content".equals(entry.getKey())) continue;
                List<Object> nested = findItems(entry.getValue());
                if (nested != null) return nested;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object findValue(Object value, String... keys) {
        if (!(value instanceof Map<?, ?> rawMap)) return null;
        Map<String, Object> map = (Map<String, Object>) rawMap;
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        for (Object nested : map.values()) {
            Object found = findValue(nested, keys);
            if (found != null) return found;
        }
        return null;
    }

    private static Integer findInteger(Object value, String... keys) {
        Object found = findValue(value, keys);
        if (found instanceof Number number) return number.intValue();
        if (found instanceof String string) {
            try { return Integer.parseInt(string); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static Boolean findBoolean(Object value, String... keys) {
        Object found = findValue(value, keys);
        if (found instanceof Boolean bool) return bool;
        if (found instanceof String string) return Boolean.parseBoolean(string);
        return null;
    }

    private static String findString(Object value, String... keys) {
        Object found = findValue(value, keys);
        return found == null ? null : String.valueOf(found);
    }
}
