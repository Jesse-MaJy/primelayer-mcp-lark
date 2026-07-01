package com.larkconnect.agent.admin;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.mcp.McpAdapter;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Service
public class AdminService {
    private final AdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenService tokenService;
    private final TokenCryptoService cryptoService;
    private final AppProperties properties;
    private final McpAdapter mcpAdapter;

    public AdminService(
            AdminRepository repository,
            PasswordEncoder passwordEncoder,
            AdminTokenService tokenService,
            TokenCryptoService cryptoService,
            AppProperties properties,
            McpAdapter mcpAdapter
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.cryptoService = cryptoService;
        this.properties = properties;
        this.mcpAdapter = mcpAdapter;
    }

    @PostConstruct
    void bootstrapAdmin() {
        repository.ensureBootstrapUser(
                properties.admin().bootstrapUsername(),
                passwordEncoder.encode(properties.admin().bootstrapPassword())
        );
    }

    public AdminDtos.LoginResponse login(AdminDtos.LoginRequest request) {
        AdminRepository.AdminUser admin = repository.findAdminByUsername(request.username());
        if (admin == null || !Status.ACTIVE.equals(admin.status()) || !passwordEncoder.matches(request.password(), admin.passwordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return new AdminDtos.LoginResponse(tokenService.issue(admin.id(), admin.username()), properties.admin().tokenTtlSeconds());
    }

    public List<Map<String, Object>> listUserBindings() {
        return repository.listUserBindings();
    }

    public void saveUserBinding(AdminDtos.UserBindingRequest request) {
        String openId = requireText(request.feishuOpenId(), "请先填写飞书 open_id");
        String compatibleUserId = hasText(request.primelayerUserId()) ? clean(request.primelayerUserId()) : openId;
        repository.upsertUserBinding(new AdminDtos.UserBindingRequest(
                request.personName(),
                openId,
                compatibleUserId,
                request.primelayerUserName(),
                defaultStatus(request.status())
        ));
    }

    public List<Map<String, Object>> listProjectTokens() {
        return repository.listProjectTokens();
    }

    public Map<String, Object> verifyProjectToken(AdminDtos.ProjectTokenVerifyRequest request) {
        String ownerType = defaultOwnerType(request.ownerType());
        String ownerId = requireText(firstNonBlank(request.ownerId(), request.primelayerUserId()), "请先选择绑定对象");
        String token = requireText(request.mcpToken(), "请先输入 MCP Token");
        return verifyToken(ownerType, ownerId, token);
    }

    public void saveProjectToken(AdminDtos.ProjectTokenRequest request) {
        String ownerType = defaultOwnerType(request.ownerType());
        String ownerId = requireText(firstNonBlank(request.ownerId(), request.primelayerUserId()), "请先选择绑定对象");
        String projectId = clean(request.projectId());
        String projectName = clean(request.projectName());
        String projectRemark = clean(request.projectRemark());
        if (!hasText(projectName) && hasText(projectRemark)) {
            projectName = projectRemark;
        }
        if (!hasText(projectId) && hasText(projectName)) {
            projectId = projectName;
        }
        boolean replaceToken = Boolean.TRUE.equals(request.replaceToken()) || hasText(request.mcpToken());
        if (!replaceToken) {
            if (!hasText(projectId) || !hasText(projectName)) {
                AdminRepository.ProjectTokenRecord existing = repository.findProjectToken(request.id())
                        .orElseThrow(() -> new IllegalArgumentException("请先确认项目备注名"));
                projectId = firstNonBlank(projectId, existing.projectId());
                projectName = firstNonBlank(projectName, existing.projectName());
                projectRemark = firstNonBlank(projectRemark, existing.projectRemark(), projectName);
            }
            repository.updateProjectTokenMetadata(normalizedProjectTokenRequest(
                    request.id(),
                    ownerType,
                    ownerId,
                    projectId,
                    projectName,
                    projectRemark,
                    "",
                    request.tokenStatus(),
                    false,
                    request.manualProjectConfirmed()
            ));
            return;
        }

        String token = requireText(request.mcpToken(), "请先输入 MCP Token");
        Map<String, Object> verification = verifyToken(ownerType, ownerId, token);
        if (!hasText(projectId) || !hasText(projectName)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> selectedProject = (Map<String, Object>) verification.get("selectedProject");
            if (selectedProject != null) {
                projectId = clean(String.valueOf(selectedProject.getOrDefault("projectId", "")));
                projectName = clean(String.valueOf(selectedProject.getOrDefault("projectName", "")));
            }
        }
        boolean manualConfirmed = Boolean.TRUE.equals(request.manualProjectConfirmed());
        if (!hasText(projectId) || !hasText(projectName)) {
            throw new IllegalArgumentException("未能识别项目，请手动填写项目备注名");
        }
        if (!manualConfirmed && !projectMatchesVerifiedCandidate(verification, projectId, projectName)) {
            throw new IllegalArgumentException("项目来自手动填写，请确认后再保存");
        }
        String ciphertext = cryptoService.encrypt(token);
        String suffix = tokenHashSuffix(token);
        String importedBy = AdminContext.current() == null ? "system" : AdminContext.current().username();
        repository.upsertProjectToken(normalizedProjectTokenRequest(
                request.id(),
                ownerType,
                ownerId,
                projectId,
                projectName,
                projectRemark,
                token,
                defaultStatus(request.tokenStatus()),
                true,
                manualConfirmed
        ), ciphertext, suffix, importedBy, manualConfirmed ? "MANUAL" : "VERIFIED", null);
    }

    private AdminDtos.ProjectTokenRequest normalizedProjectTokenRequest(
            Long id,
            String ownerType,
            String ownerId,
            String projectId,
            String projectName,
            String projectRemark,
            String token,
            String tokenStatus,
            boolean replaceToken,
            Boolean manualProjectConfirmed
    ) {
        return new AdminDtos.ProjectTokenRequest(
                id,
                ownerType,
                ownerId,
                ownerId,
                clean(projectId),
                clean(projectName),
                hasText(projectRemark) ? clean(projectRemark) : clean(projectName),
                token,
                defaultStatus(tokenStatus),
                replaceToken,
                manualProjectConfirmed
        );
    }

    public List<Map<String, Object>> listChatBindings() {
        return repository.listChatBindings();
    }

    public void saveChatBinding(AdminDtos.ChatProjectBindingRequest request) {
        String createdBy = AdminContext.current() == null ? "system" : AdminContext.current().username();
        repository.upsertChatBinding(new AdminDtos.ChatProjectBindingRequest(
                request.feishuChatId(),
                request.projectId(),
                request.projectName(),
                defaultStatus(request.status())
        ), createdBy);
    }

    public List<Map<String, Object>> listAuditLogs() {
        return repository.listAuditLogs();
    }

    public List<Map<String, Object>> listAgentTasks() {
        return repository.listAgentTasks();
    }

    public List<Map<String, Object>> listFeishuMessages() {
        return repository.listFeishuMessages();
    }

    private String defaultStatus(String status) {
        return status == null || status.isBlank() ? Status.ACTIVE : status;
    }

    private String defaultOwnerType(String ownerType) {
        return hasText(ownerType) ? clean(ownerType).toUpperCase() : "OPEN_ID";
    }

    private Map<String, Object> verifyToken(String ownerType, String ownerId, String token) {
        try {
            Map<String, Object> response = mcpAdapter.listTools(token);
            List<Map<String, Object>> tools = extractTools(response);
            List<Map<String, Object>> candidates = new ArrayList<>();
            collectProjectCandidates(response, candidates);
            collectCandidatesFromReadOnlyTools(token, tools, candidates);
            List<Map<String, Object>> normalizedCandidates = normalizeCandidates(candidates);
            List<String> warnings = new ArrayList<>();
            if (tools.isEmpty()) {
                warnings.add("MCP 已响应，但没有返回可用工具。");
            }
            if (normalizedCandidates.isEmpty()) {
                warnings.add("未能自动识别项目，请在高级填写中手动输入项目备注名。");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("ownerType", ownerType);
            result.put("ownerId", ownerId);
            result.put("primelayerUserId", ownerId);
            result.put("toolCount", tools.size());
            result.put("tokenHashSuffix", tokenHashSuffix(token));
            result.put("projectCandidates", normalizedCandidates);
            result.put("selectedProject", normalizedCandidates.size() == 1 ? normalizedCandidates.get(0) : null);
            result.put("warnings", warnings);
            result.put("tools", tools.stream().limit(12).map(this::compactTool).toList());
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Token 验证失败：" + readableError(e));
        }
    }

    private void collectCandidatesFromReadOnlyTools(String token, List<Map<String, Object>> tools, List<Map<String, Object>> candidates) {
        tools.stream()
                .map(tool -> String.valueOf(tool.getOrDefault("name", "")))
                .filter(this::looksLikeReadOnlyMetadataTool)
                .limit(4)
                .forEach(toolName -> {
                    try {
                        collectProjectCandidates(mcpAdapter.callTool(token, toolName, Map.of()), candidates);
                    } catch (Exception ignored) {
                        // Some metadata tools still need arguments. Verification only requires tools/list success.
                    }
                });
    }

    private boolean looksLikeReadOnlyMetadataTool(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase();
        if (!hasText(name)) {
            return false;
        }
        if (containsAny(name, "create", "update", "delete", "remove", "write", "set", "save", "add", "replace", "mutation")) {
            return false;
        }
        return containsAny(name, "project", "workspace", "tenant", "context", "metadata", "profile", "current", "info", "me", "user");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> resultMap) {
            Object tools = resultMap.get("tools");
            if (tools instanceof List<?> toolList) {
                return toolList.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }
        }
        Object tools = response.get("tools");
        if (tools instanceof List<?> toolList) {
            return toolList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> compactTool(Map<String, Object> tool) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("name", tool.get("name"));
        compact.put("description", tool.get("description"));
        return compact;
    }

    @SuppressWarnings("unchecked")
    private void collectProjectCandidates(Object node, List<Map<String, Object>> candidates) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            Map<String, Object> candidate = projectCandidate(map);
            if (candidate != null) {
                candidates.add(candidate);
            }
            for (Object value : map.values()) {
                collectProjectCandidates(value, candidates);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectProjectCandidates(item, candidates);
            }
        }
    }

    private Map<String, Object> projectCandidate(Map<String, Object> map) {
        String projectId = firstText(map, "projectId", "project_id", "projectID");
        String projectName = firstText(map, "projectName", "project_name", "project", "name", "title");
        boolean projectLike = map.keySet().stream().map(String::valueOf).map(String::toLowerCase).anyMatch(key -> key.contains("project"));
        if (!hasText(projectId) && projectLike) {
            projectId = firstText(map, "id", "code", "key");
        }
        if (!hasText(projectId) && !hasText(projectName)) {
            return null;
        }
        if (!projectLike && !hasText(projectId)) {
            return null;
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("projectId", hasText(projectId) ? projectId : projectName);
        candidate.put("projectName", hasText(projectName) ? projectName : projectId);
        candidate.put("workspace", firstText(map, "workspace", "workspaceName", "workspace_name"));
        candidate.put("tenant", firstText(map, "tenant", "tenantName", "tenant_name"));
        candidate.put("source", "mcp");
        return candidate;
    }

    private List<Map<String, Object>> normalizeCandidates(List<Map<String, Object>> candidates) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> candidate : candidates) {
            String projectId = clean(String.valueOf(candidate.getOrDefault("projectId", "")));
            String projectName = clean(String.valueOf(candidate.getOrDefault("projectName", "")));
            if (!hasText(projectId) || !hasText(projectName)) {
                continue;
            }
            String key = projectId + "\n" + projectName;
            if (seen.add(key)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("projectId", projectId);
                item.put("projectName", projectName);
                item.put("workspace", clean(String.valueOf(candidate.getOrDefault("workspace", ""))));
                item.put("tenant", clean(String.valueOf(candidate.getOrDefault("tenant", ""))));
                item.put("source", candidate.getOrDefault("source", "mcp"));
                normalized.add(item);
            }
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private boolean projectMatchesVerifiedCandidate(Map<String, Object> verification, String projectId, String projectName) {
        Object candidates = verification.get("projectCandidates");
        if (!(candidates instanceof List<?> candidateList)) {
            return false;
        }
        for (Object item : candidateList) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> candidate = (Map<String, Object>) rawMap;
                if (projectId.equals(clean(String.valueOf(candidate.get("projectId"))))
                        && projectName.equals(clean(String.valueOf(candidate.get("projectName"))))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                continue;
            }
            if (value != null && hasText(String.valueOf(value))) {
                return clean(String.valueOf(value));
            }
        }
        return "";
    }

    private String requireText(String value, String message) {
        String cleaned = clean(value);
        if (!hasText(cleaned)) {
            throw new IllegalArgumentException(message);
        }
        return cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String cleaned = clean(value);
            if (hasText(cleaned)) {
                return cleaned;
            }
        }
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "MCP 服务无响应或认证失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String tokenHashSuffix(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return hex.substring(hex.length() - 12);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash token", e);
        }
    }
}
