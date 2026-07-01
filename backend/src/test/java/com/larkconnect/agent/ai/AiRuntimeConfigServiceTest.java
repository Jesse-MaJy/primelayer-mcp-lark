package com.larkconnect.agent.ai;

import com.larkconnect.agent.admin.AdminDtos;
import com.larkconnect.agent.crypto.TokenCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AiRuntimeConfigServiceTest {
    private JdbcTemplate jdbcTemplate;
    private TokenCryptoService cryptoService;
    private AiRuntimeConfigService configService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:ai_runtime;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists system_config");
        jdbcTemplate.execute("""
                create table system_config (
                  config_key varchar(128) primary key,
                  config_value text not null,
                  description varchar(512),
                  is_sensitive tinyint not null default 0
                )
                """);
        cryptoService = new TokenCryptoService(jdbcTemplate);
        configService = new AiRuntimeConfigService(jdbcTemplate, cryptoService);
    }

    @Test
    void defaultsToLocalAgentWhenNoSettingsExist() {
        AiRuntimeConfigService.AiSettings settings = configService.loadSettings();

        assertThat(settings.engine()).isEqualTo(AiEngineType.LOCAL_AGENT);
        assertThat(settings.fastGptModel()).isEqualTo("fastgpt");
        assertThat(settings.fastGptTimeoutMs()).isEqualTo(30000);
        assertThat(settings.fastGptMemoryEnabled()).isTrue();
        assertThat(settings.fastGptApiKeyConfigured()).isFalse();
        assertThat(settings.fastGptApiKey()).isNull();
    }

    @Test
    void savesFastGptApiKeyEncryptedAndNeverExposesItInPublicSettings() {
        configService.saveSettings(new AdminDtos.AiSettingsRequest(
                "FASTGPT",
                "https://fastgpt.example.com",
                "fastgpt",
                "secret-key",
                12000,
                true
        ));

        AiRuntimeConfigService.AiSettings settings = configService.loadSettings();
        String storedValue = jdbcTemplate.queryForObject(
                "select config_value from system_config where config_key = ?",
                String.class,
                "ai.fastgpt.api_key"
        );

        assertThat(settings.engine()).isEqualTo(AiEngineType.FASTGPT);
        assertThat(settings.fastGptApiKeyConfigured()).isTrue();
        assertThat(settings.fastGptApiKey()).isEqualTo("secret-key");
        assertThat(settings.toPublicResponse().fastGptApiKeyConfigured()).isTrue();
        assertThat(storedValue).doesNotContain("secret-key");
        assertThat(cryptoService.decrypt(storedValue)).isEqualTo("secret-key");
    }
}
