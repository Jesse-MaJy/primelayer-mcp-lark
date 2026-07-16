package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates bounded trace output without persisting complete business records. */
final class McpTraceSampler {
    private final ObjectMapper mapper;
    McpTraceSampler(ObjectMapper mapper) { this.mapper = mapper; }

    Map<String, Object> sample(Map<String, Object> response) {
        NormalizedMcpPayload normalized = NormalizedMcpPayload.from(mapper, response);
        List<Object> samples = new ArrayList<>();
        normalized.items().stream().limit(3).forEach(item -> samples.add(sanitize(item)));
        byte[] bytes;
        try { bytes = mapper.writeValueAsBytes(response); }
        catch (Exception ignored) { bytes = String.valueOf(response).getBytes(StandardCharsets.UTF_8); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("returnedCount", normalized.items().size());
        result.put("reportedTotalCount", normalized.reportedTotalCount());
        result.put("responseBytes", bytes.length);
        result.put("contentHash", sha256(bytes));
        result.put("samples", samples);
        result.put("traceSampled", true);
        return result;
    }

    private Object sanitize(Object value) {
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> safe = new LinkedHashMap<>();
        map.forEach((key, nested) -> {
            if (nested instanceof String text) safe.put(String.valueOf(key),
                    text.length() > 200 ? "[omitted " + text.length() + " chars]" : text);
            else if (!(nested instanceof Map<?, ?>) && !(nested instanceof Iterable<?>)) safe.put(String.valueOf(key), nested);
        });
        return safe;
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ignored) { return Integer.toHexString(java.util.Arrays.hashCode(bytes)); }
    }
}
