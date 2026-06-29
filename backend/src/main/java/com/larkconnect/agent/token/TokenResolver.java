package com.larkconnect.agent.token;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
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

    public ResolvedContext resolve(String openId, String chatId, String chatType, DeepSeekPlan plan, int maxProjects) {
        UserBinding user = findUser(openId);
        if (user == null) {
            return ResolvedContext.error("你还没有绑定 Primelayer 账号，请联系管理员完成绑定。");
        }
        if ("group".equals(chatType)) {
            ChatProject project = findChatProject(chatId);
            if (project == null) {
                return ResolvedContext.error("当前飞书群还没有绑定 Primelayer 项目，请联系管理员完成群项目绑定。");
            }
            TokenEntry token = findToken(user.primelayerUserId(), project.projectId());
            if (token == null) {
                return ResolvedContext.error("你当前没有该 Primelayer 项目的 MCP 访问配置，请联系管理员确认项目 token。");
            }
            return ResolvedContext.ok(user.primelayerUserId(), List.of(token));
        }
        if ("all_accessible_projects".equals(plan.projectScope())) {
            List<TokenEntry> tokens = findTokens(user.primelayerUserId(), maxProjects);
            return tokens.isEmpty()
                    ? ResolvedContext.error("你当前没有可查询的 Primelayer 项目 MCP 访问配置。")
                    : ResolvedContext.ok(user.primelayerUserId(), tokens);
        }
        String projectHint = plan.projectHints().isEmpty() ? null : plan.projectHints().get(0);
        if (projectHint == null || projectHint.isBlank()) {
            return ResolvedContext.error("我还无法判断你要查询哪个项目，请补充项目名称。");
        }
        TokenEntry token = findTokenByHint(user.primelayerUserId(), projectHint);
        return token == null
                ? ResolvedContext.error("你当前没有该 Primelayer 项目的 MCP 访问配置，请联系管理员确认项目 token。")
                : ResolvedContext.ok(user.primelayerUserId(), List.of(token));
    }

    private UserBinding findUser(String openId) {
        List<UserBinding> rows = jdbcTemplate.query(
                "select primelayer_user_id from user_binding where feishu_open_id = ? and status = ?",
                (rs, rowNum) -> new UserBinding(rs.getString("primelayer_user_id")),
                openId, Status.ACTIVE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ChatProject findChatProject(String chatId) {
        List<ChatProject> rows = jdbcTemplate.query(
                "select project_id, project_name from feishu_chat_project_binding where feishu_chat_id = ? and status = ?",
                (rs, rowNum) -> new ChatProject(rs.getString("project_id"), rs.getString("project_name")),
                chatId, Status.ACTIVE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TokenEntry findToken(String primelayerUserId, String projectId) {
        List<TokenEntry> rows = mapTokens(jdbcTemplate.queryForList("""
                select id, project_id, project_name, mcp_token_ciphertext from project_mcp_token
                where primelayer_user_id = ? and project_id = ? and token_status = ?
                """, primelayerUserId, projectId, Status.ACTIVE));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TokenEntry findTokenByHint(String primelayerUserId, String hint) {
        List<TokenEntry> rows = mapTokens(jdbcTemplate.queryForList("""
                select id, project_id, project_name, mcp_token_ciphertext from project_mcp_token
                where primelayer_user_id = ? and token_status = ? and (project_id = ? or project_name like ?)
                order by project_name limit 1
                """, primelayerUserId, Status.ACTIVE, hint, "%" + hint + "%"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<TokenEntry> findTokens(String primelayerUserId, int limit) {
        return mapTokens(jdbcTemplate.queryForList("""
                select id, project_id, project_name, mcp_token_ciphertext from project_mcp_token
                where primelayer_user_id = ? and token_status = ?
                order by project_name limit ?
                """, primelayerUserId, Status.ACTIVE, limit));
    }

    private List<TokenEntry> mapTokens(List<Map<String, Object>> rows) {
        List<TokenEntry> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            tokens.add(new TokenEntry(
                    ((Number) row.get("id")).longValue(),
                    row.get("project_id").toString(),
                    row.get("project_name").toString(),
                    cryptoService.decrypt(row.get("mcp_token_ciphertext").toString())
            ));
        }
        return tokens;
    }

    private record UserBinding(String primelayerUserId) {}
    private record ChatProject(String projectId, String projectName) {}

    public record TokenEntry(Long tokenId, String projectId, String projectName, String token) {}

    public record ResolvedContext(String primelayerUserId, List<TokenEntry> tokens, String errorMessage) {
        public static ResolvedContext ok(String primelayerUserId, List<TokenEntry> tokens) {
            return new ResolvedContext(primelayerUserId, tokens, null);
        }

        public static ResolvedContext error(String error) {
            return new ResolvedContext(null, List.of(), error);
        }

        public boolean hasError() {
            return errorMessage != null;
        }
    }
}
