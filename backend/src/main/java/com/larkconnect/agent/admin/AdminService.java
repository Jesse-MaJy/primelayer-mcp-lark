package com.larkconnect.agent.admin;

import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    private final AdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenService tokenService;
    private final TokenCryptoService cryptoService;
    private final AppProperties properties;

    public AdminService(
            AdminRepository repository,
            PasswordEncoder passwordEncoder,
            AdminTokenService tokenService,
            TokenCryptoService cryptoService,
            AppProperties properties
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.cryptoService = cryptoService;
        this.properties = properties;
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
        repository.upsertUserBinding(new AdminDtos.UserBindingRequest(
                request.feishuOpenId(),
                request.primelayerUserId(),
                request.primelayerUserName(),
                defaultStatus(request.status())
        ));
    }

    public List<Map<String, Object>> listProjectTokens() {
        return repository.listProjectTokens();
    }

    public void saveProjectToken(AdminDtos.ProjectTokenRequest request) {
        String ciphertext = cryptoService.encrypt(request.mcpToken());
        String suffix = tokenHashSuffix(request.mcpToken());
        String importedBy = AdminContext.current() == null ? "system" : AdminContext.current().username();
        repository.upsertProjectToken(new AdminDtos.ProjectTokenRequest(
                request.primelayerUserId(),
                request.projectId(),
                request.projectName(),
                request.mcpToken(),
                defaultStatus(request.tokenStatus())
        ), ciphertext, suffix, importedBy);
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

    private String defaultStatus(String status) {
        return status == null || status.isBlank() ? Status.ACTIVE : status;
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
