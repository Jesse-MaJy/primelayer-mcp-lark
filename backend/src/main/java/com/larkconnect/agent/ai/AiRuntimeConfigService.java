package com.larkconnect.agent.ai;

import com.larkconnect.agent.admin.AdminDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiRuntimeConfigService {
    public static final String DEFAULT_MODEL = "deepseek-v4-pro";
    public static final List<String> SUPPORTED_MODELS = List.of("deepseek-v4-pro", "deepseek-v4-flash");
    private static final String MODEL_KEY = "ai.deepseek.model";

    private final JdbcTemplate jdbcTemplate;

    public AiRuntimeConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String currentModel() {
        String configured = jdbcTemplate.query(
                "select config_value from system_config where config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                MODEL_KEY
        );
        return configured != null && SUPPORTED_MODELS.contains(configured) ? configured : DEFAULT_MODEL;
    }

    public AdminDtos.AiSettingsResponse publicSettings() {
        return new AdminDtos.AiSettingsResponse(currentModel(), SUPPORTED_MODELS);
    }

    public AdminDtos.AiSettingsResponse saveSettings(AdminDtos.AiSettingsRequest request) {
        String model = request == null ? null : request.deepSeekModel();
        if (!SUPPORTED_MODELS.contains(model)) {
            throw new IllegalArgumentException("不支持的 DeepSeek 模型：" + model);
        }
        jdbcTemplate.update("""
                insert into system_config(config_key, config_value, description, is_sensitive)
                values (?, ?, ?, 0)
                on duplicate key update config_value = values(config_value), description = values(description), is_sensitive = 0
                """, MODEL_KEY, model, "Selected DeepSeek model for unified agent queries.");
        return publicSettings();
    }
}
