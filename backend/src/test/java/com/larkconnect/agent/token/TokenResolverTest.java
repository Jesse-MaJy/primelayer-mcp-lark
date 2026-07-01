package com.larkconnect.agent.token;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenResolverTest {
    private JdbcTemplate jdbcTemplate;
    private TokenResolver resolver;
    private TokenCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:token_resolver;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists system_config");
        jdbcTemplate.execute("drop table if exists user_binding");
        jdbcTemplate.execute("drop table if exists project_mcp_token");
        jdbcTemplate.execute("drop table if exists feishu_chat_project_binding");
        jdbcTemplate.execute("""
                create table system_config (
                  config_key varchar(128) primary key,
                  config_value text not null,
                  description varchar(512),
                  is_sensitive tinyint not null default 0
                )
                """);
        jdbcTemplate.execute("""
                create table user_binding (
                  id bigint auto_increment primary key,
                  feishu_open_id varchar(128) not null unique,
                  primelayer_user_id varchar(128) not null,
                  status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table project_mcp_token (
                  id bigint auto_increment primary key,
                  owner_type varchar(32) not null default 'PRIMELAYER_USER',
                  owner_id varchar(128),
                  primelayer_user_id varchar(128) not null,
                  project_id varchar(128) not null,
                  project_name varchar(256) not null,
                  project_remark varchar(256),
                  mcp_token_ciphertext text not null,
                  token_status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table feishu_chat_project_binding (
                  id bigint auto_increment primary key,
                  feishu_chat_id varchar(128) not null unique,
                  project_id varchar(128) not null,
                  project_name varchar(256) not null,
                  status varchar(32) not null
                )
                """);
        cryptoService = new TokenCryptoService(jdbcTemplate);
        resolver = new TokenResolver(jdbcTemplate, cryptoService);
    }

    @Test
    void reportsMissingOpenIdToken() {
        TokenResolver.McpConfigCheckResult result = resolver.checkMcpConfig("ou_missing", "", "p2p", 5);

        assertThat(result.configured()).isFalse();
        assertThat(result.primelayerUserId()).isNull();
        assertThat(result.reason()).contains("open_id 下没有 ACTIVE MCP Token");
    }

    @Test
    void reportsMissingTokenForBoundUser() {
        jdbcTemplate.update("insert into user_binding(feishu_open_id, primelayer_user_id, status) values (?, ?, ?)", "ou_1", "Jiayi", Status.ACTIVE);

        TokenResolver.McpConfigCheckResult result = resolver.checkMcpConfig("ou_1", "", "p2p", 5);

        assertThat(result.configured()).isFalse();
        assertThat(result.primelayerUserId()).isNull();
        assertThat(result.reason()).contains("open_id 下没有 ACTIVE MCP Token");
    }

    @Test
    void stillFallsBackToLegacyPrimelayerBinding() {
        jdbcTemplate.update("insert into user_binding(feishu_open_id, primelayer_user_id, status) values (?, ?, ?)", "ou_1", "Jiayi", Status.ACTIVE);
        jdbcTemplate.update("""
                insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, "PRIMELAYER_USER", "Jiayi", "Jiayi", "Roche", "Roche", cryptoService.encrypt("mcp-token"), Status.ACTIVE);

        TokenResolver.McpConfigCheckResult result = resolver.checkMcpConfig("ou_1", "", "p2p", 5);

        assertThat(result.configured()).isTrue();
        assertThat(result.primelayerUserId()).isEqualTo("Jiayi");
        assertThat(result.ownerType()).isEqualTo("PRIMELAYER_USER");
    }

    @Test
    void returnsProjectsForConfiguredUser() {
        jdbcTemplate.update("""
                insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, "OPEN_ID", "ou_1", "ou_1", "Roche", "Roche", cryptoService.encrypt("mcp-token"), Status.ACTIVE);

        TokenResolver.McpConfigCheckResult result = resolver.checkMcpConfig("ou_1", "", "p2p", 5);

        assertThat(result.configured()).isTrue();
        assertThat(result.primelayerUserId()).isEqualTo("ou_1");
        assertThat(result.ownerType()).isEqualTo("OPEN_ID");
        assertThat(result.projects()).extracting(TokenResolver.ProjectRef::projectId).containsExactly("Roche");
    }

    @Test
    void resolvesScreenshotOpenIdDirectOwnerToken() {
        String openId = "ou_32cfb3b88c89c9a0b9852c12b9575d5c";
        jdbcTemplate.update("""
                insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, "OPEN_ID", openId, openId, "1", "Roche", cryptoService.encrypt("mcp-token"), Status.ACTIVE);

        TokenResolver.McpConfigCheckResult result = resolver.checkMcpConfig(openId, "", "p2p", 5);

        assertThat(result.configured()).isTrue();
        assertThat(result.ownerType()).isEqualTo("OPEN_ID");
        assertThat(result.ownerId()).isEqualTo(openId);
        assertThat(result.projects()).extracting(TokenResolver.ProjectRef::projectId).containsExactly("1");
    }

    @Test
    void usesOnlyP2pProjectWhenQuestionHasNoSpecificProjectName() {
        insertConfiguredUserToken("ou_1", "Jiayi", "Roche", "Roche");
        DeepSeekPlan plan = new DeepSeekPlan(
                "project_query",
                "single_project",
                List.of(),
                List.of(new DeepSeekPlan.ToolCall("primelayer.query_project_health", Map.of("question", "今天项目施工情况"))),
                false,
                null,
                "normal"
        );

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", "", "p2p", plan, 5);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId).containsExactly("Roche");
    }

    @Test
    void stripsProjectSuffixWhenMatchingHint() {
        insertConfiguredUserToken("ou_1", "Jiayi", "Roche", "Roche");
        DeepSeekPlan plan = new DeepSeekPlan(
                "project_query",
                "single_project",
                List.of("Roche项目"),
                List.of(new DeepSeekPlan.ToolCall("primelayer.query_project_health", Map.of("question", "Roche项目施工情况"))),
                false,
                null,
                "normal"
        );

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", "", "p2p", plan, 5);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).extracting(TokenResolver.TokenEntry::projectId).containsExactly("Roche");
    }

    // =========================================================================
    // Regression tests for projectRemark / pinyin / no-match-list bug fix
    // =========================================================================

    @Test
    @DisplayName("场景A: projectRemark 精确匹配（核心修复——用户说'罗诊项目'匹配备注'罗诊'）")
    void matchesByProjectRemarkWhenHintStripsSuffix() {
        insertTokenWithRemark("ou_1", "roche-001", "Roche", "罗诊");
        DeepSeekPlan plan = buildPlan(List.of("罗诊项目"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).projectId()).isEqualTo("roche-001");
    }

    @Test
    @DisplayName("场景B: projectName 包含匹配（hint '罗诊' 匹配 projectName '罗诊项目组'）")
    void matchesByProjectNameContains() {
        insertTokenWithRemark("ou_1", "p1", "罗诊项目组", "");
        DeepSeekPlan plan = buildPlan(List.of("罗诊"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).projectId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("场景C: 反向包含匹配（hint '罗诊项目情况' 包含 projectName '罗诊'）")
    void matchesByReverseContains() {
        insertTokenWithRemark("ou_1", "p1", "罗诊", "");
        DeepSeekPlan plan = buildPlan(List.of("罗诊项目情况"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).projectId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("场景D: 拼音首字母匹配（hint 'lz' 匹配 projectName '罗诊'）")
    void matchesByPinyinInitials() {
        insertTokenWithRemark("ou_1", "p1", "罗诊", "someRemark");
        DeepSeekPlan plan = buildPlan(List.of("lz"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).projectId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("场景E: 匹配失败时错误信息列出所有可用项目（含 projectName 和 projectRemark）")
    void noMatchReturnsAvailableProjectList() {
        insertTokenWithRemark("ou_1", "p1", "Roche", "罗诊");
        insertTokenWithRemark("ou_1", "p2", "Biocha", "生物查");
        DeepSeekPlan plan = buildPlan(List.of("不存在的项目"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isTrue();
        assertThat(result.errorMessage()).contains("Roche");
        assertThat(result.errorMessage()).contains("罗诊");
        assertThat(result.errorMessage()).contains("Biocha");
        assertThat(result.errorMessage()).contains("生物查");
    }

    @Test
    @DisplayName("场景F: 用户无任何 Token 时返回 noTokenMessage")
    void noTokenReturnsNoTokenMessage() {
        DeepSeekPlan plan = buildPlan(List.of("任意"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isTrue();
        assertThat(result.errorMessage()).contains("请在「人员配置」中");
    }

    @Test
    @DisplayName("场景G: 通用项目 hint（'项目'）单 Token 时直接返回")
    void genericHintWithSingleTokenReturnsDirectly() {
        insertTokenWithRemark("ou_1", "p1", "Roche", "罗诊");
        DeepSeekPlan plan = buildPlan(List.of("项目"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).projectId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("场景H: 通用项目 hint（'项目'）多 Token 时返回歧义错误")
    void genericHintWithMultipleTokensReturnsAmbiguityError() {
        insertTokenWithRemark("ou_1", "p1", "Roche", "罗诊");
        insertTokenWithRemark("ou_1", "p2", "Biocha", "生物查");
        DeepSeekPlan plan = buildPlan(List.of("项目"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isTrue();
        assertThat(result.errorMessage()).contains("我还无法判断你要查询哪个项目");
    }

    @Test
    @DisplayName("场景I: 空备注边界——projectRemark='' + projectName='罗诊' + hint='lz' 应走拼音匹配命中，而非 (f) 步空串误匹配")
    void emptyRemarkDoesNotCauseFalseMatchAndPinyinStillWorks() {
        insertTokenWithRemark("ou_1", "p1", "罗诊", "");
        DeepSeekPlan plan = buildPlan(List.of("lz"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.tokens().get(0).projectId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("场景J: 空备注诱饵——trap Token(projectRemark='') 排在前面，hint='lz' 不应误匹配 trap，应走拼音匹配到 '罗诊'")
    void emptyRemarkTrapDoesNotShadowPinyinMatch() {
        // trap Token 排在前面（按 project_name 字母序 "TrapProject" < "罗诊"，且 SQL order by project_name）
        insertTokenWithRemark("ou_1", "trap", "TrapProject", "");
        insertTokenWithRemark("ou_1", "real", "罗诊", "");
        DeepSeekPlan plan = buildPlan(List.of("lz"));

        TokenResolver.ResolvedContext result = resolver.resolve("ou_1", null, "p2p", plan, 20);

        assertThat(result.hasError()).isFalse();
        assertThat(result.tokens()).hasSize(1);
        // 修复前：findTokenBySingleHint (f) 步 "lz".contains("") == true → 误返回 trap
        // 修复后：(f) 步被 !isEmpty() 守卫跳过 → findTokenByPinyinInitials 拼音匹配 "罗诊"→"lz" → 返回 real
        assertThat(result.tokens().get(0).projectId()).isEqualTo("real");
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private DeepSeekPlan buildPlan(List<String> projectHints) {
        return new DeepSeekPlan(
                "project_query",
                "single_project",
                projectHints,
                List.of(new DeepSeekPlan.ToolCall("primelayer.query_project_health", Map.of("question", "test"))),
                false,
                null,
                "normal"
        );
    }

    private void insertTokenWithRemark(String openId, String projectId, String projectName, String projectRemark) {
        jdbcTemplate.update("""
                insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, project_remark, mcp_token_ciphertext, token_status)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, "OPEN_ID", openId, openId, projectId, projectName, projectRemark, cryptoService.encrypt("mcp-token"), Status.ACTIVE);
    }

    private void insertConfiguredUserToken(String openId, String primelayerUserId, String projectId, String projectName) {
        jdbcTemplate.update("""
                insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, "OPEN_ID", openId, openId, projectId, projectName, cryptoService.encrypt("mcp-token"), Status.ACTIVE);
    }
}
