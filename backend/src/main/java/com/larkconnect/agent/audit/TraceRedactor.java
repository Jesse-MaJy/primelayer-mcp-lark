package com.larkconnect.agent.audit;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class TraceRedactor {
    private static final String REDACTED = "***REDACTED***";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "token", "authorization", "auth", "secret", "password", "apikey", "api_key",
            "ciphertext", "mcptoken", "mcp_token", "accesstoken", "access_token",
            "refreshtoken", "refresh_token", "mcp_token_ciphertext");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[^\\s,;\\\"]+");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)((?:access_token|refresh_token|api_key|apikey|password|secret|token)\\s*[:=]\\s*)([^\\s,;]+)");

    public Object redact(Object value) {
        return redactValue(value, null);
    }

    private Object redactValue(Object value, String key) {
        if (key != null && sensitiveKey(key)) return REDACTED;
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof CharSequence text) return redactText(text.toString());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((entryKey, entryValue) -> {
                String name = String.valueOf(entryKey);
                copy.put(name, redactValue(entryValue, name));
            });
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(redactValue(item, null)));
            return copy;
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) copy.add(redactValue(Array.get(value, i), null));
            return copy;
        }
        return redactText(String.valueOf(value));
    }

    private boolean sensitiveKey(String key) {
        String normalized = key.replace("-", "_").toLowerCase(Locale.ROOT);
        String compact = normalized.replace("_", "");
        return SENSITIVE_KEYS.contains(normalized) || SENSITIVE_KEYS.contains(compact)
                || normalized.endsWith("_token") || normalized.endsWith("_secret")
                || normalized.endsWith("_password") || normalized.endsWith("_ciphertext")
                || compact.endsWith("ciphertext");
    }

    private String redactText(String text) {
        String redacted = BEARER.matcher(text).replaceAll("$1" + REDACTED);
        return INLINE_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
    }
}
