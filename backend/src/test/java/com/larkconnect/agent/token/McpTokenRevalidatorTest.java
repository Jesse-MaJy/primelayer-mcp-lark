package com.larkconnect.agent.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.mcp.McpAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenRevalidatorTest {
    @Test
    void fillsMissingMcpIdentityFromReadOnlyAccountTool() {
        Fixture fixture = new Fixture(false, false);
        fixture.insert("token-one", "FAILED", "无法从 MCP 账号信息解析 primelayer_user_id");

        fixture.revalidator.revalidateMissingIdentities();

        Map<String, Object> row = fixture.jdbc.queryForMap("select * from project_mcp_token");
        assertThat(row.get("mcp_user_id")).isEqualTo("mcp-user-1");
        assertThat(row.get("verify_error")).isNull();
        fixture.revalidator.shutdown();
    }

    @Test
    void recoversTokenWhenIdentityIsNotExposed() {
        Fixture fixture = new Fixture(true, false);
        fixture.insert("token-without-identity", "FAILED", "无法从 MCP 账号信息解析 primelayer_user_id");

        fixture.revalidator.revalidateMissingIdentities();

        Map<String, Object> row = fixture.jdbc.queryForMap("select * from project_mcp_token");
        assertThat(row.get("mcp_user_id")).isNull();
        assertThat(row.get("verify_status")).isEqualTo("VERIFIED");
        assertThat(row.get("verify_error")).isNull();
        fixture.revalidator.shutdown();
    }

    @Test
    void marksTokenFailedWhenToolsListFails() {
        Fixture fixture = new Fixture(false, true);
        fixture.insert("token-bad", "FAILED", "无法从 MCP 账号信息解析 primelayer_user_id");

        fixture.revalidator.revalidateMissingIdentities();

        Map<String, Object> row = fixture.jdbc.queryForMap("select * from project_mcp_token");
        assertThat(row.get("verify_status")).isEqualTo("FAILED");
        assertThat(String.valueOf(row.get("verify_error"))).contains("token invalid");
        fixture.revalidator.shutdown();
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TokenCryptoService crypto;
        final McpTokenRevalidator revalidator;

        Fixture(boolean identityMissing, boolean listFailure) {
            jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:revalidate" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
            jdbc.execute("create table system_config(config_key varchar(128) primary key, config_value text not null, description varchar(512), is_sensitive tinyint default 0)");
            jdbc.execute("create table project_mcp_token(id bigint auto_increment primary key, mcp_user_id varchar(128), mcp_token_ciphertext text, token_status varchar(32), verify_status varchar(32), last_verified_at timestamp, verify_error varchar(512))");
            crypto = new TokenCryptoService(jdbc);
            AppProperties properties = new AppProperties(
                    new AppProperties.Admin(1, "a", "a"), new AppProperties.Agent(20, 1000, 1000),
                    new AppProperties.Feishu("", "", "", "", false), new AppProperties.DeepSeek("", ""),
                    new AppProperties.Mcp("http://localhost", "X-API-Key"));
            McpAdapter adapter = new McpAdapter(properties, RestClient.builder(), new ObjectMapper()) {
                @Override public Map<String, Object> listTools(String token) {
                    if (listFailure) throw new IllegalStateException("token invalid");
                    return Map.of("result", Map.of("tools", List.of(Map.of("name", "get_account_info"))));
                }
                @Override public Map<String, Object> callTool(String token, String tool, Map<String, Object> args) {
                    return identityMissing ? Map.of("result", Map.of())
                            : Map.of("result", Map.of("primelayer_user_id", "mcp-user-1"));
                }
            };
            revalidator = new McpTokenRevalidator(jdbc, crypto, adapter);
        }

        void insert(String token, String verifyStatus, String verifyError) {
            jdbc.update("insert into project_mcp_token(mcp_user_id,mcp_token_ciphertext,token_status,verify_status,verify_error) values(null,?,'ACTIVE',?,?)",
                    crypto.encrypt(token), verifyStatus, verifyError);
        }
    }
}
