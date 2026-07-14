package com.larkconnect.agent.mcp;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record AsyncTaskState(String value, boolean known, boolean terminal, boolean successful) {
    private static final Set<String> WAITING = Set.of("PENDING", "QUEUED", "RUNNING", "PROCESSING", "IN_PROGRESS");
    private static final Set<String> SUCCEEDED = Set.of("SUCCESS", "SUCCEEDED", "COMPLETED", "DONE", "FINISHED");
    private static final Set<String> FAILED = Set.of("FAILED", "ERROR", "CANCELLED", "CANCELED", "TIMEOUT");

    public static AsyncTaskState from(Object payload) {
        Object raw = find(payload);
        if (raw == null) return new AsyncTaskState(null, false, false, false);
        String normalized = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (WAITING.contains(normalized)) return new AsyncTaskState(normalized, true, false, false);
        if (SUCCEEDED.contains(normalized)) return new AsyncTaskState(normalized, true, true, true);
        if (FAILED.contains(normalized)) return new AsyncTaskState(normalized, true, true, false);
        return new AsyncTaskState(normalized, false, false, false);
    }

    public boolean waiting() { return known && !terminal; }

    private static Object find(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : Set.of("task_status", "taskStatus", "status", "state")) {
                if (map.containsKey(key) && !(map.get(key) instanceof Map<?, ?>)) return map.get(key);
            }
            for (Object nested : map.values()) {
                Object found = find(nested);
                if (found != null) return found;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                Object found = find(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
