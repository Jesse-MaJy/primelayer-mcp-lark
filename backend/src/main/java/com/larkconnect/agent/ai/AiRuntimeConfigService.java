package com.larkconnect.agent.ai;

import com.larkconnect.agent.admin.AdminDtos;
import com.larkconnect.agent.crypto.TokenCryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AiRuntimeConfigService {
    private static final String ENGINE_KEY = "ai.engine";
    private static final String FASTGPT_BASE_URL_KEY = "ai.fastgpt.base_url";
    private static final String FASTGPT_MODEL_KEY = "ai.fastgpt.model";
    private static final String FASTGPT_API_KEY = "ai.fastgpt.api_key";
    private static final String FASTGPT_TIMEOUT_MS_KEY = "ai.fastgpt.timeout_ms";
    private static final String FASTGPT_MEMORY_ENABLED_KEY = "ai.fastgpt.memory_enabled";

    private final JdbcTemplate jdbcTemplate;
    private final TokenCryptoService cryptoService;

    public AiRuntimeConfigService(JdbcTemplate jdbcTemplate, TokenCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    public AiSettings loadSettings() {
        String encryptedApiKey = read(FASTGPT_API_KEY);
        String apiKey = null;
        if (hasText(encryptedApiKey)) {
            apiKey = cryptoService.decrypt(encryptedApiKey);
        }
        return new AiSettings(
                AiEngineType.from(read(ENGINE_KEY)),
                defaultText(read(FASTGPT_BASE_URL_KEY), ""),
                defaultText(read(FASTGPT_MODEL_KEY), "fastgpt"),
                apiKey,
                hasText(encryptedApiKey),
                parseInt(read(FASTGPT_TIMEOUT_MS_KEY), 30000),
                parseBoolean(read(FASTGPT_MEMORY_ENABLED_KEY), true)
        );
    }

    public AdminDtos.AiSettingsResponse publicSettings() {
        return loadSettings().toPublicResponse();
    }

    public AdminDtos.AiSettingsResponse saveSettings(AdminDtos.AiSettingsRequest request) {
        AiSettings current = loadSettings();
        AiEngineType engine = AiEngineType.from(request.engine());
        String baseUrl = clean(request.fastGptBaseUrl());
        String model = defaultText(clean(request.fastGptModel()), "fastgpt");
        int timeoutMs = request.fastGptTimeoutMs() == null ? 30000 : Math.max(1000, request.fastGptTimeoutMs());
        boolean memoryEnabled = request.fastGptMemoryEnabled() == null || Boolean.TRUE.equals(request.fastGptMemoryEnabled());

        upsert(ENGINE_KEY, engine.name(), "Selected AI answer engine.", false);
        upsert(FASTGPT_BASE_URL_KEY, baseUrl, "FastGPT service base URL.", false);
        upsert(FASTGPT_MODEL_KEY, model, "FastGPT model name.", false);
        upsert(FASTGPT_TIMEOUT_MS_KEY, String.valueOf(timeoutMs), "FastGPT request timeout in milliseconds.", false);
        upsert(FASTGPT_MEMORY_ENABLED_KEY, String.valueOf(memoryEnabled), "Whether FastGPT should reuse chat memory.", false);
        if (hasText(request.fastGptApiKey())) {
            upsert(FASTGPT_API_KEY, cryptoService.encrypt(request.fastGptApiKey().trim()), "Encrypted FastGPT API key.", true);
        } else if (current.fastGptApiKeyConfigured()) {
            upsert(FASTGPT_API_KEY, read(FASTGPT_API_KEY), "Encrypted FastGPT API key.", true);
        }
        return loadSettings().toPublicResponse();
    }

    private String read(String key) {
        if (jdbcTemplate == null) {
            return null;
        }
        return jdbcTemplate.query("select config_value from system_config where config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                key);
    }

    private void upsert(String key, String value, String description, boolean sensitive) {
        jdbcTemplate.update("""
                insert into system_config(config_key, config_value, description, is_sensitive)
                values (?, ?, ?, ?)
                on duplicate key update config_value = values(config_value),
                  description = values(description), is_sensitive = values(is_sensitive)
                """, key, value == null ? "" : value, description, sensitive ? 1 : 0);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private int parseInt(String value, int fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record AiSettings(
            AiEngineType engine,
            String fastGptBaseUrl,
            String fastGptModel,
            String fastGptApiKey,
            boolean fastGptApiKeyConfigured,
            int fastGptTimeoutMs,
            boolean fastGptMemoryEnabled
    ) {
        public AdminDtos.AiSettingsResponse toPublicResponse() {
            return new AdminDtos.AiSettingsResponse(
                    engine.name(),
                    fastGptBaseUrl,
                    fastGptModel,
                    fastGptApiKeyConfigured,
                    fastGptTimeoutMs,
                    fastGptMemoryEnabled
            );
        }
    }
}
