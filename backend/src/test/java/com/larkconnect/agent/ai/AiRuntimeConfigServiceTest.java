package com.larkconnect.agent.ai;

import com.larkconnect.agent.admin.AdminDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRuntimeConfigServiceTest {
    private JdbcTemplate jdbcTemplate;
    private AiRuntimeConfigService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:deepseek_runtime;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists system_config");
        jdbcTemplate.execute("create table system_config (config_key varchar(128) primary key, config_value text not null, description varchar(512), is_sensitive tinyint not null default 0)");
        service = new AiRuntimeConfigService(jdbcTemplate);
    }

    @Test
    void defaultsToV4ProAndExposesOnlySupportedModels() {
        AdminDtos.AiSettingsResponse settings = service.publicSettings();

        assertThat(settings.deepSeekModel()).isEqualTo("deepseek-v4-pro");
        assertThat(settings.supportedModels()).containsExactly("deepseek-v4-pro", "deepseek-v4-flash");
    }

    @Test
    void savesSelectedDeepSeekModel() {
        AdminDtos.AiSettingsResponse settings = service.saveSettings(new AdminDtos.AiSettingsRequest("deepseek-v4-flash"));

        assertThat(settings.deepSeekModel()).isEqualTo("deepseek-v4-flash");
        assertThat(service.currentModel()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void rejectsUnsupportedModel() {
        assertThatThrownBy(() -> service.saveSettings(new AdminDtos.AiSettingsRequest("deepseek-chat")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 DeepSeek 模型");
    }
}
