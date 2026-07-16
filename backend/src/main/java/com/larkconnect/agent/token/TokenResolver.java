package com.larkconnect.agent.token;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.crypto.TokenCryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TokenResolver {
    private final JdbcTemplate jdbcTemplate;
    private final TokenCryptoService cryptoService;

    public TokenResolver(JdbcTemplate jdbcTemplate, TokenCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    /** Returns the complete eligible catalog. Query limits are applied only after scope selection. */
    public ResolvedContext resolveCatalog(String openId) {
        if (!hasText(openId)) return ResolvedContext.error("飞书 open_id 为空，无法查找 MCP Token。");
        List<TokenEntry> tokens = mapTokens(jdbcTemplate.queryForList("""
                select id, feishu_open_id, mcp_user_id, project_id, project_name, project_remark,
                       mcp_token_ciphertext
                from project_mcp_token
                where feishu_open_id = ?
                  and token_status = ?
                  and verify_status in ('VERIFIED', 'MANUAL')
                order by project_name, project_id, id
                """, openId, Status.ACTIVE));
        return tokens.isEmpty()
                ? ResolvedContext.error("当前飞书 open_id 下没有已验证且可用的 ACTIVE MCP Token。请在「人员配置」中完成 Token 配置或重新验证。")
                : ResolvedContext.ok(null, tokens);
    }

    public ResolvedContext resolveCandidates(String openId, String chatId, String chatType, int maxProjects) {
        ResolvedContext catalog = resolveCatalog(openId);
        if (catalog.hasError()) return catalog;
        int limit = Math.max(1, maxProjects);
        return ResolvedContext.ok(null, catalog.tokens().stream().limit(limit).toList());
    }

    public ResolvedContext resolveSelected(String openId, List<String> projectIds) {
        ResolvedContext catalog = resolveCatalog(openId);
        if (catalog.hasError()) return catalog;
        if (projectIds == null || projectIds.isEmpty()) return catalog;
        List<TokenEntry> selected = catalog.tokens().stream()
                .filter(token -> projectIds.contains(token.projectId()))
                .toList();
        if (selected.size() != projectIds.stream().distinct().count()) {
            return ResolvedContext.error("请求包含未授权或不可用的项目 MCP Token。");
        }
        return ResolvedContext.ok(null, selected);
    }

    public McpConfigCheckResult checkMcpConfig(String openId, String chatId, String chatType, int maxProjects) {
        ResolvedContext catalog = resolveCatalog(openId);
        if (catalog.hasError()) {
            return McpConfigCheckResult.missing(openId, null, null, null, catalog.errorMessage());
        }
        List<ProjectRef> projects = catalog.tokens().stream()
                .limit(Math.max(1, maxProjects))
                .map(token -> new ProjectRef(token.projectId(), token.projectName()))
                .toList();
        return McpConfigCheckResult.configured(openId, null, "OPEN_ID", openId, projects);
    }

    public void updateVerificationStatus(Long tokenId, String verifyStatus, String verifyError) {
        jdbcTemplate.update("""
                update project_mcp_token
                set verify_status = ?, last_verified_at = current_timestamp, verify_error = ?
                where id = ?
                """, verifyStatus, verifyError, tokenId);
    }

    private List<TokenEntry> mapTokens(List<Map<String, Object>> rows) {
        List<TokenEntry> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            tokens.add(new TokenEntry(
                    ((Number) row.get("id")).longValue(),
                    text(row.get("feishu_open_id")),
                    text(row.get("mcp_user_id")),
                    text(row.get("project_id")),
                    text(row.get("project_name")),
                    text(row.get("project_remark")),
                    cryptoService.decrypt(text(row.get("mcp_token_ciphertext")))
            ));
        }
        return List.copyOf(tokens);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    public record TokenEntry(Long tokenId, String feishuOpenId, String mcpUserId, String projectId,
                             String projectName, String projectRemark, String token) {
        public TokenEntry(Long tokenId, String projectId, String projectName, String projectRemark, String token) {
            this(tokenId, "", "", projectId, projectName, projectRemark, token);
        }
    }

    public record ProjectRef(String projectId, String projectName) {}

    public record McpConfigCheckResult(boolean configured, String reason, String openId,
                                       String primelayerUserId, String ownerType, String ownerId,
                                       List<ProjectRef> projects) {
        public static McpConfigCheckResult configured(String openId, String contextUserId, String ownerType,
                                                      String ownerId, List<ProjectRef> projects) {
            return new McpConfigCheckResult(true, "已找到可用项目 MCP Token。", openId, contextUserId,
                    ownerType, ownerId, List.copyOf(projects));
        }
        public static McpConfigCheckResult missing(String openId, String contextUserId, String ownerType,
                                                   String ownerId, String reason) {
            return new McpConfigCheckResult(false, reason, openId, contextUserId, ownerType, ownerId, List.of());
        }
    }

    public record ResolvedContext(String primelayerUserId, List<TokenEntry> tokens, String errorMessage) {
        public static ResolvedContext ok(String contextUserId, List<TokenEntry> tokens) {
            return new ResolvedContext(contextUserId, List.copyOf(tokens), null);
        }
        public static ResolvedContext error(String error) { return new ResolvedContext(null, List.of(), error); }
        public boolean hasError() { return errorMessage != null; }
    }
}
