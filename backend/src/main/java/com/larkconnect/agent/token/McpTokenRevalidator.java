package com.larkconnect.agent.token;

import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.mcp.McpAdapter;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class McpTokenRevalidator {
    private final JdbcTemplate jdbc;
    private final TokenCryptoService crypto;
    private final McpAdapter mcp;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mcp-token-revalidator");
        thread.setDaemon(true);
        return thread;
    });

    public McpTokenRevalidator(JdbcTemplate jdbc, TokenCryptoService crypto, McpAdapter mcp) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.mcp = mcp;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleMissingIdentityRevalidation() {
        executor.submit(this::revalidateMissingIdentities);
    }

    void revalidateMissingIdentities() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, mcp_token_ciphertext, verify_status
                from project_mcp_token
                where token_status = 'ACTIVE'
                  and (mcp_user_id is null or trim(mcp_user_id) = '')
                  and verify_status = 'FAILED'
                  and verify_error like '%primelayer_user_id%'
                order by id
                """);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            try {
                String token = crypto.decrypt(String.valueOf(row.get("mcp_token_ciphertext")));
                String userId = resolveUserId(token);
                String previousStatus = String.valueOf(row.get("verify_status"));
                String verifiedStatus = "FAILED".equals(previousStatus) ? "VERIFIED" : previousStatus;
                jdbc.update("""
                        update project_mcp_token set mcp_user_id=?, verify_status=?,
                          last_verified_at=current_timestamp, verify_error=null where id=?
                        """, userId.isBlank() ? null : userId, verifiedStatus, id);
            } catch (Exception failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                jdbc.update("""
                        update project_mcp_token set verify_status='FAILED', last_verified_at=current_timestamp,
                          verify_error=? where id=?
                        """, abbreviate(message, 500), id);
            }
        }
    }

    private String resolveUserId(String token) {
        Map<String, Object> listed = mcp.listTools(token);
        String direct = findUserId(listed);
        if (!direct.isBlank()) return direct;
        for (Map<String, Object> tool : extractTools(listed)) {
            String name = String.valueOf(tool.getOrDefault("name", ""));
            String lower = name.toLowerCase();
            if (!(lower.contains("account") || lower.contains("profile") || lower.contains("user")
                    || lower.equals("me") || lower.contains("current"))) continue;
            try {
                String found = findUserId(mcp.callTool(token, name, Map.of()));
                if (!found.isBlank()) return found;
            } catch (Exception ignored) {
                // Continue through other read-only identity tools.
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
        }
        return List.of();
    }

    private String findUserId(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (String key : List.of("primelayer_user_id", "primelayerUserId", "user_id", "userId")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
            }
            for (Object value : map.values()) {
                String found = findUserId(value);
                if (!found.isBlank()) return found;
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                String found = findUserId(value);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    private String abbreviate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }
}
