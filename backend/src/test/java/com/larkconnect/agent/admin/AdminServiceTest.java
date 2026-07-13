package com.larkconnect.agent.admin;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.mcp.McpAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminServiceTest {
    private JdbcTemplate jdbcTemplate;
    private TokenCryptoService cryptoService;
    private AdminService adminService;
    private CountingMcpAdapter mcpAdapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:admin_service;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists system_config");
        jdbcTemplate.execute("drop table if exists admin_user");
        jdbcTemplate.execute("drop table if exists project_mcp_token");
        jdbcTemplate.execute("""
                create table system_config (
                  config_key varchar(128) primary key,
                  config_value text not null,
                  description varchar(512),
                  is_sensitive tinyint not null default 0
                )
                """);
        jdbcTemplate.execute("""
                create table admin_user (
                  id bigint auto_increment primary key,
                  username varchar(64) not null unique,
                  password_hash varchar(255) not null,
                  status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table project_mcp_token (
                  id bigint auto_increment primary key,
                  owner_type varchar(32) not null,
                  owner_id varchar(128) not null,
                  primelayer_user_id varchar(128) not null,
                  project_id varchar(128) not null,
                  project_name varchar(256) not null,
                  project_remark varchar(256),
                  mcp_token_ciphertext text not null,
                  token_hash_suffix varchar(32),
                  token_status varchar(32) not null,
                  imported_by varchar(128),
                  imported_at timestamp default current_timestamp,
                  last_used_at timestamp null,
                  verify_status varchar(32) default 'VERIFIED',
                  last_verified_at timestamp null,
                  verify_error varchar(512) null
                )
                """);
        AdminRepository repository = new AdminRepository(jdbcTemplate);
        cryptoService = new TokenCryptoService(jdbcTemplate);
        AppProperties properties = new AppProperties(
                new AppProperties.Admin(28800, "admin", "admin"),
                new AppProperties.Agent(5, 10000, 10000),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("", ""),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key")
        );
        mcpAdapter = new CountingMcpAdapter(properties);
        adminService = new AdminService(
                repository,
                new NoopPasswordEncoder(),
                new AdminTokenService(properties),
                cryptoService,
                properties,
                mcpAdapter
        );
    }

    @Test
    void editingExistingTokenWithoutMcpTokenKeepsSecret() {
        adminService.saveProjectToken(projectTokenRequest(null, "ou_1", "Roche", "Roche", "token-one", true, Status.ACTIVE));
        Long id = jdbcTemplate.queryForObject("select id from project_mcp_token", Long.class);
        String originalCiphertext = jdbcTemplate.queryForObject("select mcp_token_ciphertext from project_mcp_token where id = ?", String.class, id);
        String originalSuffix = jdbcTemplate.queryForObject("select token_hash_suffix from project_mcp_token where id = ?", String.class, id);

        adminService.saveProjectToken(projectTokenRequest(id, "ou_1", "Roche", "Roche 上海", "", false, Status.DISABLED));

        Map<String, Object> row = jdbcTemplate.queryForMap("select * from project_mcp_token where id = ?", id);
        assertThat(row.get("project_name")).isEqualTo("Roche 上海");
        assertThat(row.get("token_status")).isEqualTo(Status.DISABLED);
        assertThat(row.get("mcp_token_ciphertext")).isEqualTo(originalCiphertext);
        assertThat(row.get("token_hash_suffix")).isEqualTo(originalSuffix);
        assertThat(cryptoService.decrypt(String.valueOf(row.get("mcp_token_ciphertext")))).isEqualTo("token-one");
        assertThat(mcpAdapter.listToolsCalls()).isEqualTo(1);
    }

    @Test
    void replacingExistingTokenUpdatesSecretAndDoesNotDuplicate() {
        adminService.saveProjectToken(projectTokenRequest(null, "ou_1", "Roche", "Roche", "token-one", true, Status.ACTIVE));
        Long id = jdbcTemplate.queryForObject("select id from project_mcp_token", Long.class);
        String originalSuffix = jdbcTemplate.queryForObject("select token_hash_suffix from project_mcp_token where id = ?", String.class, id);

        adminService.saveProjectToken(projectTokenRequest(id, "ou_1", "Roche", "Roche", "token-two", true, Status.ACTIVE));

        Map<String, Object> row = jdbcTemplate.queryForMap("select * from project_mcp_token where id = ?", id);
        assertThat(cryptoService.decrypt(String.valueOf(row.get("mcp_token_ciphertext")))).isEqualTo("token-two");
        assertThat(row.get("token_hash_suffix")).isNotEqualTo(originalSuffix);
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_mcp_token", Integer.class)).isEqualTo(1);
        assertThat(mcpAdapter.listToolsCalls()).isEqualTo(2);
    }

    @Test
    void repeatedSaveForSameOwnerAndProjectUpdatesOneRow() {
        adminService.saveProjectToken(projectTokenRequest(null, "ou_1", "Roche", "Roche", "token-one", true, Status.ACTIVE));
        adminService.saveProjectToken(projectTokenRequest(null, "ou_1", "Roche", "Roche Updated", "token-two", true, Status.ACTIVE));

        assertThat(jdbcTemplate.queryForObject("select count(*) from project_mcp_token", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select project_name from project_mcp_token", String.class)).isEqualTo("Roche Updated");
    }

    private AdminDtos.ProjectTokenRequest projectTokenRequest(Long id, String ownerId, String projectId, String projectName, String token, boolean replaceToken, String status) {
        return new AdminDtos.ProjectTokenRequest(
                id,
                "OPEN_ID",
                ownerId,
                ownerId,
                projectId,
                projectName,
                projectName,
                token,
                status,
                replaceToken,
                true
        );
    }

    private static class CountingMcpAdapter extends McpAdapter {
        private int listToolsCalls;

        CountingMcpAdapter(AppProperties properties) {
            super(properties, RestClient.builder(), new ObjectMapper());
        }

        @Override
        public Map<String, Object> listTools(String token) {
            listToolsCalls++;
            return Map.of(
                    "result", Map.of(
                            "tools", List.of(Map.of("name", "get_account_info")),
                            "projectId", "Roche",
                            "projectName", "Roche"
                    )
            );
        }

        int listToolsCalls() {
            return listToolsCalls;
        }
    }

    private static class NoopPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return String.valueOf(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return String.valueOf(rawPassword).equals(encodedPassword);
        }
    }
}
