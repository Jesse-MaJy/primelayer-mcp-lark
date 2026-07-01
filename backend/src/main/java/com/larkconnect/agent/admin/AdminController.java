package com.larkconnect.agent.admin;

import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final AiRuntimeConfigService aiRuntimeConfigService;
    private final ChainTraceService chainTraceService;

    public AdminController(AdminService adminService, AiRuntimeConfigService aiRuntimeConfigService, ChainTraceService chainTraceService) {
        this.adminService = adminService;
        this.aiRuntimeConfigService = aiRuntimeConfigService;
        this.chainTraceService = chainTraceService;
    }

    @PostMapping("/login")
    public ApiResponse<AdminDtos.LoginResponse> login(@Valid @RequestBody AdminDtos.LoginRequest request) {
        return ApiResponse.ok(adminService.login(request));
    }

    @GetMapping("/user-bindings")
    public ApiResponse<List<Map<String, Object>>> listUserBindings() {
        return ApiResponse.ok(adminService.listUserBindings());
    }

    @PostMapping("/user-bindings")
    public ApiResponse<Void> saveUserBinding(@Valid @RequestBody AdminDtos.UserBindingRequest request) {
        adminService.saveUserBinding(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/project-tokens")
    public ApiResponse<List<Map<String, Object>>> listProjectTokens() {
        return ApiResponse.ok(adminService.listProjectTokens());
    }

    @PostMapping("/project-tokens/verify")
    public ApiResponse<Map<String, Object>> verifyProjectToken(@Valid @RequestBody AdminDtos.ProjectTokenVerifyRequest request) {
        return ApiResponse.ok(adminService.verifyProjectToken(request));
    }

    @PostMapping("/project-tokens")
    public ApiResponse<Void> saveProjectToken(@Valid @RequestBody AdminDtos.ProjectTokenRequest request) {
        adminService.saveProjectToken(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/chat-project-bindings")
    public ApiResponse<List<Map<String, Object>>> listChatBindings() {
        return ApiResponse.ok(adminService.listChatBindings());
    }

    @PostMapping("/chat-project-bindings")
    public ApiResponse<Void> saveChatBinding(@Valid @RequestBody AdminDtos.ChatProjectBindingRequest request) {
        adminService.saveChatBinding(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<Map<String, Object>>> listAuditLogs() {
        return ApiResponse.ok(adminService.listAuditLogs());
    }

    @GetMapping("/agent-tasks")
    public ApiResponse<List<Map<String, Object>>> listAgentTasks() {
        return ApiResponse.ok(adminService.listAgentTasks());
    }

    @GetMapping("/feishu-messages")
    public ApiResponse<List<Map<String, Object>>> listFeishuMessages() {
        return ApiResponse.ok(adminService.listFeishuMessages());
    }

    @GetMapping("/ai-settings")
    public ApiResponse<AdminDtos.AiSettingsResponse> aiSettings() {
        return ApiResponse.ok(aiRuntimeConfigService.publicSettings());
    }

    @PutMapping("/ai-settings")
    public ApiResponse<AdminDtos.AiSettingsResponse> saveAiSettings(@RequestBody AdminDtos.AiSettingsRequest request) {
        return ApiResponse.ok(aiRuntimeConfigService.saveSettings(request));
    }

    @GetMapping("/chain-trace/{requestId}")
    public ApiResponse<Map<String, Object>> getChainTrace(@PathVariable String requestId) {
        return chainTraceService.load(requestId)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("链调记录不存在"));
    }
}
