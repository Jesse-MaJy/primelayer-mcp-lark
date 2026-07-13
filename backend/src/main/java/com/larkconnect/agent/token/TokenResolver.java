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
    private static final String OWNER_OPEN_ID = "OPEN_ID";
    private static final String OWNER_CHAT_ID = "CHAT_ID";
    private static final String OWNER_LEGACY_USER = "PRIMELAYER_USER";

    private final JdbcTemplate jdbcTemplate;
    private final TokenCryptoService cryptoService;

    public TokenResolver(JdbcTemplate jdbcTemplate, TokenCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    public ResolvedContext resolveCandidates(String openId, String chatId, String chatType, int maxProjects) {
        OwnerTokens ownerTokens = findDirectOwnerTokens(openId, chatId, chatType, maxProjects);
        if (!ownerTokens.tokens().isEmpty()) {
            return ResolvedContext.ok(ownerTokens.contextUserId(), ownerTokens.tokens());
        }
        ResolvedContext legacy = resolveLegacyCandidates(openId, chatId, chatType, maxProjects);
        if (!legacy.hasError()) {
            return legacy;
        }
        return ResolvedContext.error(noTokenMessage(openId, chatId, chatType));
    }

    public McpConfigCheckResult checkMcpConfig(String openId, String chatId, String chatType, int maxProjects) {
        OwnerTokens ownerTokens = findDirectOwnerTokens(openId, chatId, chatType, maxProjects);
        if (!ownerTokens.tokens().isEmpty()) {
            return McpConfigCheckResult.configured(
                    openId,
                    ownerTokens.contextUserId(),
                    ownerTokens.ownerType(),
                    ownerTokens.ownerId(),
                    ownerTokens.tokens().stream().map(token -> new ProjectRef(token.projectId(), token.projectName())).toList()
            );
        }
        ResolvedContext legacy = resolveLegacyCandidates(openId, chatId, chatType, maxProjects);
        if (!legacy.hasError()) {
            return McpConfigCheckResult.configured(
                    openId,
                    legacy.primelayerUserId(),
                    OWNER_LEGACY_USER,
                    legacy.primelayerUserId(),
                    legacy.tokens().stream().map(token -> new ProjectRef(token.projectId(), token.projectName())).toList()
            );
        }
        return McpConfigCheckResult.missing(openId, null, null, null, noTokenMessage(openId, chatId, chatType));
    }

    public void updateVerificationStatus(Long tokenId, String verifyStatus, String verifyError) {
        jdbcTemplate.update("""
                update project_mcp_token
                set verify_status = ?, last_verified_at = current_timestamp, verify_error = ?
                where id = ?
                """, verifyStatus, verifyError, tokenId);
    }

    private OwnerTokens findDirectOwnerTokens(String openId, String chatId, String chatType, int maxProjects) {
        if ("group".equals(chatType) && hasText(chatId)) {
            List<TokenEntry> chatTokens = findTokensByOwner(OWNER_CHAT_ID, chatId, maxProjects);
            if (!chatTokens.isEmpty()) {
                return new OwnerTokens(OWNER_CHAT_ID, chatId, chatId, chatTokens);
            }
        }
        List<TokenEntry> openIdTokens = findTokensByOwner(OWNER_OPEN_ID, openId, maxProjects);
        return new OwnerTokens(OWNER_OPEN_ID, openId, openId, openIdTokens);
    }

    private ResolvedContext resolveLegacyCandidates(String openId, String chatId, String chatType, int maxProjects) {
        UserBinding user = findUser(openId);
        if (user == null) {
            return ResolvedContext.error("未找到旧版人员绑定。");
        }
        if ("group".equals(chatType)) {
            ChatProject project = findChatProject(chatId);
            if (project != null) {
                TokenEntry token = findLegacyToken(user.primelayerUserId(), project.projectId());
                if (token != null) {
                    return ResolvedContext.ok(user.primelayerUserId(), List.of(token));
                }
            }
        }
        List<TokenEntry> tokens = findLegacyTokens(user.primelayerUserId(), maxProjects);
        return tokens.isEmpty()
                ? ResolvedContext.error("旧版人员绑定下没有 ACTIVE MCP Token。")
                : ResolvedContext.ok(user.primelayerUserId(), tokens);
    }

    private String noTokenMessage(String openId, String chatId, String chatType) {
        if ("group".equals(chatType) && hasText(chatId)) {
            return "当前飞书群或发言人的 open_id 下没有 ACTIVE MCP Token。请在「人员配置」中为群 chat_id 或人员 open_id 配置 MCP Token，并填写项目备注名。";
        }
        return "当前飞书 open_id 下没有 ACTIVE MCP Token。请在「人员配置」中为该 open_id 配置 MCP Token，并填写项目备注名。";
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

    private TokenEntry findLegacyToken(String primelayerUserId, String projectId) {
        List<TokenEntry> rows = mapTokens(jdbcTemplate.queryForList("""
                select id, project_id, project_name, project_remark, mcp_token_ciphertext from project_mcp_token
                where primelayer_user_id = ? and project_id = ? and token_status = ?
                """, primelayerUserId, projectId, Status.ACTIVE));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<TokenEntry> findLegacyTokens(String primelayerUserId, int limit) {
        return mapTokens(jdbcTemplate.queryForList("""
                select id, project_id, project_name, project_remark, mcp_token_ciphertext from project_mcp_token
                where primelayer_user_id = ? and token_status = ?
                order by project_name limit ?
                """, primelayerUserId, Status.ACTIVE, limit));
    }

    private List<TokenEntry> findTokensByOwner(String ownerType, String ownerId, int limit) {
        if (!hasText(ownerId)) {
            return List.of();
        }
        return mapTokens(jdbcTemplate.queryForList("""
                select id, project_id, project_name, project_remark, mcp_token_ciphertext from project_mcp_token
                where owner_type = ? and owner_id = ? and token_status = ?
                order by project_name limit ?
                """, ownerType, ownerId, Status.ACTIVE, limit));
    }

    private TokenEntry findTokenByHint(List<TokenEntry> tokens, String hint) {
        TokenEntry token = findTokenBySingleHint(tokens, hint);
        if (token != null) {
            return token;
        }
        String normalizedHint = normalizeProjectHint(hint);
        if (!normalizedHint.equals(hint)) {
            token = findTokenBySingleHint(tokens, normalizedHint);
            if (token != null) {
                return token;
            }
        }
        // 拼音首字母匹配作为最后手段
        return findTokenByPinyinInitials(tokens, normalizedHint);
    }

    private TokenEntry findTokenBySingleHint(List<TokenEntry> tokens, String hint) {
        String value = normalizeProjectHint(hint);
        if (!hasText(value)) {
            return null;
        }
        String normalizedValue = value.toLowerCase();
        for (TokenEntry token : tokens) {
            String projectId = token.projectId() == null ? "" : token.projectId().trim();
            String projectName = token.projectName() == null ? "" : token.projectName().trim();
            String projectRemark = token.projectRemark() == null ? "" : token.projectRemark().trim();
            // a) 精确匹配 projectId
            if (value.equals(projectId) || value.equalsIgnoreCase(projectId)) {
                return token;
            }
            // b) 精确匹配 projectName
            if (value.equals(projectName) || value.equalsIgnoreCase(projectName)) {
                return token;
            }
            // c) 精确匹配 projectRemark
            if (value.equals(projectRemark) || value.equalsIgnoreCase(projectRemark)) {
                return token;
            }
            // d) 包含匹配 projectName（空字符串跳过，避免 "" 恒匹配）
            if (!projectName.isEmpty()
                    && (projectName.contains(value) || projectName.toLowerCase().contains(normalizedValue))) {
                return token;
            }
            // e) 包含匹配 projectRemark（空字符串跳过）
            if (!projectRemark.isEmpty()
                    && (projectRemark.contains(value) || projectRemark.toLowerCase().contains(normalizedValue))) {
                return token;
            }
            // f) 反向包含（hint 包含项目名/备注）— 仅对非空字段检查
            if ((!projectName.isEmpty() && (value.contains(projectName) || normalizedValue.contains(projectName.toLowerCase())))
                    || (!projectRemark.isEmpty() && (value.contains(projectRemark) || normalizedValue.contains(projectRemark.toLowerCase())))) {
                return token;
            }
        }
        return null;
    }

    /**
     * 拼音首字母匹配：将 hint 和项目名/备注分别转为拼音首字母后比较。
     * 例如用户输入 "lz" 可匹配备注 "罗诊"（拼音首字母 lz）。
     * 仅在前述所有匹配均未命中时作为兜底手段。
     */
    private TokenEntry findTokenByPinyinInitials(List<TokenEntry> tokens, String hint) {
        String value = normalizeProjectHint(hint);
        if (!hasText(value)) {
            return null;
        }
        String hintPinyin = getPinyinInitials(value);
        if (hintPinyin.equalsIgnoreCase(value)) {
            // hint 不含中文，拼音首字母与原值相同。
            // 仍需检查项目名/备注是否为中文且拼音首字母匹配（如 hint="lz" 匹配备注="罗诊"）。
            boolean hasChineseToken = false;
            for (TokenEntry token : tokens) {
                String projectName = token.projectName() == null ? "" : token.projectName().trim();
                String projectRemark = token.projectRemark() == null ? "" : token.projectRemark().trim();
                if (containsChinese(projectName) || containsChinese(projectRemark)) {
                    hasChineseToken = true;
                    break;
                }
            }
            if (!hasChineseToken) {
                return null;
            }
        }
        for (TokenEntry token : tokens) {
            String projectName = token.projectName() == null ? "" : token.projectName().trim();
            String projectRemark = token.projectRemark() == null ? "" : token.projectRemark().trim();
            String namePinyin = getPinyinInitials(projectName);
            String remarkPinyin = getPinyinInitials(projectRemark);
            if (hintPinyin.equalsIgnoreCase(namePinyin) || hintPinyin.equalsIgnoreCase(remarkPinyin)) {
                return token;
            }
        }
        return null;
    }

    /**
     * 构建匹配失败的错误信息，列出用户所有可用项目以引导重试。
     */
    private String buildNoMatchMessage(String projectHint, List<TokenEntry> tokens) {
        String displayHint = projectHint == null ? "" : projectHint;
        StringBuilder sb = new StringBuilder();
        sb.append("当前绑定对象下没有匹配『").append(displayHint).append("』的 ACTIVE MCP Token。");
        if (!tokens.isEmpty()) {
            sb.append("您当前可查询的项目有：");
            for (int i = 0; i < tokens.size(); i++) {
                TokenEntry t = tokens.get(i);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(t.projectName());
                if (hasText(t.projectRemark())) {
                    sb.append("（备注：").append(t.projectRemark()).append("）");
                }
            }
            sb.append("。请用项目备注名重新提问。");
        } else {
            sb.append("请联系管理员确认项目名称。");
        }
        return sb.toString();
    }

    /**
     * 将文本中的每个字符转为拼音首字母（小写）。非中文字符保持原样（小写）。
     * 例如 "罗诊" → "lz"，"Roche" → "roche"，"lz" → "lz"。
     * 使用 GBK 编码区间映射，不引入第三方库。
     */
    private static String getPinyinInitials(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(getSinglePinyinInitial(text.charAt(i)));
        }
        return sb.toString();
    }

    private static String getSinglePinyinInitial(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
            return String.valueOf(ch);
        }
        if (ch >= 'A' && ch <= 'Z') {
            return String.valueOf(Character.toLowerCase(ch));
        }
        if (ch < 0x4E00 || ch > 0x9FFF) {
            return String.valueOf(ch);
        }
        try {
            byte[] bytes = String.valueOf(ch).getBytes("GBK");
            if (bytes.length < 2) {
                return String.valueOf(ch);
            }
            int code = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
            if (code < 0xB0A1) {
                return String.valueOf(ch);
            }
            if (code < 0xB0C5) return "a";
            if (code < 0xB2C1) return "b";
            if (code < 0xB4EE) return "c";
            if (code < 0xB6EA) return "d";
            if (code < 0xB7A2) return "e";
            if (code < 0xB8C1) return "f";
            if (code < 0xB9FE) return "g";
            if (code < 0xBBF7) return "h";
            if (code < 0xBFA6) return "j";
            if (code < 0xC0AC) return "k";
            if (code < 0xC2E8) return "l";
            if (code < 0xC4C3) return "m";
            if (code < 0xC5B6) return "n";
            if (code < 0xC5BE) return "o";
            if (code < 0xC6DA) return "p";
            if (code < 0xC8BB) return "q";
            if (code < 0xC8F6) return "r";
            if (code < 0xCBFA) return "s";
            if (code < 0xCDDA) return "t";
            if (code < 0xCEF4) return "w";
            if (code < 0xD1B9) return "x";
            if (code < 0xD4D1) return "y";
            return "z";
        } catch (Exception e) {
            return String.valueOf(ch);
        }
    }

    private static boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private List<TokenEntry> mapTokens(List<Map<String, Object>> rows) {
        List<TokenEntry> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object remarkObj = row.get("project_remark");
            tokens.add(new TokenEntry(
                    ((Number) row.get("id")).longValue(),
                    row.get("project_id").toString(),
                    row.get("project_name").toString(),
                    remarkObj == null ? "" : remarkObj.toString(),
                    cryptoService.decrypt(row.get("mcp_token_ciphertext").toString())
            ));
        }
        return tokens;
    }

    private boolean isGenericProjectHint(String hint) {
        String value = normalizeProjectHint(hint);
        return value.isBlank()
                || value.equals("项目")
                || value.equals("当前项目")
                || value.equals("这个项目")
                || value.equals("该项目")
                || value.equals("今天项目")
                || value.equals("今日项目")
                || value.equals("本项目");
    }

    private String normalizeProjectHint(String hint) {
        if (hint == null) {
            return "";
        }
        String value = hint.trim();
        if (value.endsWith("项目") && value.length() > 2) {
            value = value.substring(0, value.length() - 2).trim();
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record UserBinding(String primelayerUserId) {}
    private record ChatProject(String projectId, String projectName) {}
    private record OwnerTokens(String ownerType, String ownerId, String contextUserId, List<TokenEntry> tokens) {}

    public record TokenEntry(Long tokenId, String projectId, String projectName, String projectRemark, String token) {}

    public record ProjectRef(String projectId, String projectName) {}

    public record McpConfigCheckResult(
            boolean configured,
            String reason,
            String openId,
            String primelayerUserId,
            String ownerType,
            String ownerId,
            List<ProjectRef> projects
    ) {
        public static McpConfigCheckResult configured(String openId, String contextUserId, String ownerType, String ownerId, List<ProjectRef> projects) {
            return new McpConfigCheckResult(true, "已找到可用项目 MCP Token。", openId, contextUserId, ownerType, ownerId, projects);
        }

        public static McpConfigCheckResult missing(String openId, String contextUserId, String ownerType, String ownerId, String reason) {
            return new McpConfigCheckResult(false, reason, openId, contextUserId, ownerType, ownerId, List.of());
        }
    }

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
