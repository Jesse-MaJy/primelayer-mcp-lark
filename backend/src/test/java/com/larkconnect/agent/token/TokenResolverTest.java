package com.larkconnect.agent.token;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.crypto.TokenCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class TokenResolverTest {
    private JdbcTemplate jdbc;
    private TokenResolver resolver;
    private TokenCryptoService crypto;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:token_resolver;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists system_config");
        jdbc.execute("drop table if exists user_binding");
        jdbc.execute("drop table if exists project_mcp_token");
        jdbc.execute("drop table if exists feishu_chat_project_binding");
        jdbc.execute("create table system_config (config_key varchar(128) primary key, config_value text not null, description varchar(512), is_sensitive tinyint not null default 0)");
        jdbc.execute("create table user_binding (id bigint auto_increment primary key, feishu_open_id varchar(128) unique, status varchar(32))");
        jdbc.execute("create table project_mcp_token (id bigint auto_increment primary key, feishu_open_id varchar(128), mcp_user_id varchar(128), project_id varchar(128), project_name varchar(256), project_remark varchar(256), mcp_token_ciphertext text, token_status varchar(32), verify_status varchar(32))");
        crypto = new TokenCryptoService(jdbc);
        resolver = new TokenResolver(jdbc, crypto);
    }

    @Test
    void reportsMissingOpenIdToken() {
        assertThat(resolver.checkMcpConfig("missing", "", "p2p", 5).configured()).isFalse();
    }

    @Test
    void resolvesAllAuthorizedCandidateProjectsWithoutPlanner() {
        insert("u1", "P1", "项目一", "VERIFIED", "mcp-u1");
        insert("u1", "P2", "项目二", "MANUAL", "mcp-u1");

        TokenResolver.ResolvedContext result = resolver.resolveCandidates("u1", "", "p2p", 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId).containsExactly("P1", "P2");
    }

    @Test
    void groupQueriesUseOnlySpeakerOpenIdTokens() {
        insert("u1", "PRIVATE", "用户项目", "VERIFIED", "mcp-u1");
        insert("another", "OTHER", "其他用户项目", "VERIFIED", "mcp-u2");

        TokenResolver.ResolvedContext result = resolver.resolveCandidates("u1", "g1", "group", 20);

        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId).containsExactly("PRIVATE");
    }

    @Test
    void excludesUnverifiedButAcceptsIdentityMissingTokens() {
        insert("u1", "GOOD", "可用", "VERIFIED", "mcp-u1");
        insert("u1", "FAILED", "失败", "FAILED", "mcp-u1");
        insert("u1", "PENDING", "待补验", "VERIFIED", "");

        TokenResolver.ResolvedContext result = resolver.resolveCatalog("u1");

        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId)
                .containsExactlyInAnyOrder("GOOD", "PENDING");
    }

    private void insert(String openId, String projectId, String projectName, String verifyStatus, String mcpUserId) {
        jdbc.update("insert into project_mcp_token(feishu_open_id, mcp_user_id, project_id, project_name, project_remark, mcp_token_ciphertext, token_status, verify_status) values (?, ?, ?, ?, '', ?, ?, ?)",
                openId, mcpUserId, projectId, projectName, crypto.encrypt("token-" + projectId), Status.ACTIVE, verifyStatus);
    }
}
