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
        jdbc.execute("create table user_binding (id bigint auto_increment primary key, feishu_open_id varchar(128) unique, primelayer_user_id varchar(128), status varchar(32))");
        jdbc.execute("create table project_mcp_token (id bigint auto_increment primary key, owner_type varchar(32), owner_id varchar(128), primelayer_user_id varchar(128), project_id varchar(128), project_name varchar(256), project_remark varchar(256), mcp_token_ciphertext text, token_status varchar(32))");
        jdbc.execute("create table feishu_chat_project_binding (id bigint auto_increment primary key, feishu_chat_id varchar(128) unique, project_id varchar(128), project_name varchar(256), status varchar(32))");
        crypto = new TokenCryptoService(jdbc);
        resolver = new TokenResolver(jdbc, crypto);
    }

    @Test
    void reportsMissingOpenIdToken() {
        assertThat(resolver.checkMcpConfig("missing", "", "p2p", 5).configured()).isFalse();
    }

    @Test
    void resolvesAllAuthorizedCandidateProjectsWithoutPlanner() {
        insert("OPEN_ID", "u1", "P1", "项目一");
        insert("OPEN_ID", "u1", "P2", "项目二");

        TokenResolver.ResolvedContext result = resolver.resolveCandidates("u1", "", "p2p", 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId).containsExactly("P1", "P2");
    }

    @Test
    void groupOwnerTokensTakePrecedenceOverOpenIdTokens() {
        insert("OPEN_ID", "u1", "PRIVATE", "私聊项目");
        insert("CHAT_ID", "g1", "GROUP", "群项目");

        TokenResolver.ResolvedContext result = resolver.resolveCandidates("u1", "g1", "group", 20);

        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId).containsExactly("GROUP");
    }

    private void insert(String ownerType, String ownerId, String projectId, String projectName) {
        jdbc.update("insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, project_remark, mcp_token_ciphertext, token_status) values (?, ?, ?, ?, ?, '', ?, ?)",
                ownerType, ownerId, ownerId, projectId, projectName, crypto.encrypt("token-" + projectId), Status.ACTIVE);
    }
}
