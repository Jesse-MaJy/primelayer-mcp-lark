package com.larkconnect.agent.admin;

import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.ai.PromptDomain;
import com.larkconnect.agent.ai.PromptReplayService;
import com.larkconnect.agent.ai.PromptStage;
import com.larkconnect.agent.ai.PromptTemplateService;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.common.ApiResponse;
import com.larkconnect.agent.feishu.AnswerFeedbackRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final AiRuntimeConfigService aiRuntimeConfigService;
    private final ChainTraceService chainTraceService;
    private final AnswerFeedbackRepository answerFeedbackRepository;
    private final PromptTemplateService promptTemplateService;
    private final PromptReplayService promptReplayService;

    @Autowired
    public AdminController(AdminService adminService, AiRuntimeConfigService aiRuntimeConfigService,
                           ChainTraceService chainTraceService, AnswerFeedbackRepository answerFeedbackRepository,
                           PromptTemplateService promptTemplateService, PromptReplayService promptReplayService) {
        this.adminService = adminService;
        this.aiRuntimeConfigService = aiRuntimeConfigService;
        this.chainTraceService = chainTraceService;
        this.answerFeedbackRepository = answerFeedbackRepository;
        this.promptTemplateService = promptTemplateService;
        this.promptReplayService = promptReplayService;
    }

    public AdminController(AdminService adminService, AiRuntimeConfigService aiRuntimeConfigService,
                           ChainTraceService chainTraceService, AnswerFeedbackRepository answerFeedbackRepository) {
        this(adminService, aiRuntimeConfigService, chainTraceService, answerFeedbackRepository, null, null);
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

    @GetMapping("/feishu-messages/{requestId}/feedback")
    public ApiResponse<List<AnswerFeedbackRepository.FeedbackDetail>> listMessageFeedback(@PathVariable String requestId) {
        return ApiResponse.ok(answerFeedbackRepository.listDetails(requestId));
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

    @GetMapping("/chain-trace/{requestId}/events/{eventId}")
    public ApiResponse<Map<String, Object>> getChainTraceEvent(@PathVariable String requestId,
                                                               @PathVariable String eventId) {
        return chainTraceService.loadEvent(requestId, eventId)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("链调节点不存在"));
    }

    @GetMapping("/prompt-templates")
    public ApiResponse<Map<String, Object>> promptTemplates() {
        return ApiResponse.ok(Map.of(
                "versions", promptTemplateService.list(),
                "snapshots", promptReplayService.listSnapshots(),
                "allowedVariables", PromptTemplateService.ALLOWED_VARIABLES,
                "securityWarning", "回放快照使用 AES-GCM 加密，但主密钥与密文暂存于同一数据库，不能防御数据库整体泄露。"));
    }

    @PostMapping("/prompt-templates/{stage}/{domain}/versions")
    public ApiResponse<Map<String, Object>> createPromptVersion(@PathVariable String stage,
                                                                @PathVariable String domain,
                                                                @RequestBody AdminDtos.PromptVersionRequest request) {
        return ApiResponse.ok(promptTemplateService.createVersion(promptStage(stage), promptDomain(domain),
                request == null ? null : request.content()));
    }

    @PostMapping("/prompt-templates/versions/{id}/publish")
    public ApiResponse<Map<String, Object>> publishPromptVersion(@PathVariable long id,
                                                                 @RequestBody(required = false) AdminDtos.PromptActionRequest request) {
        return ApiResponse.ok(promptTemplateService.publish(id, request == null ? null : request.note(), false));
    }

    @PostMapping("/prompt-templates/versions/{id}/rollback")
    public ApiResponse<Map<String, Object>> rollbackPromptVersion(@PathVariable long id,
                                                                  @RequestBody(required = false) AdminDtos.PromptActionRequest request) {
        return ApiResponse.ok(promptTemplateService.publish(id, request == null ? null : request.note(), true));
    }

    @PostMapping("/prompt-templates/versions/{id}/replay")
    public ApiResponse<Map<String, Object>> replayPromptVersion(@PathVariable long id,
                                                                @RequestBody AdminDtos.PromptReplayRequest request) {
        if (request == null || request.snapshotId() == null) throw new IllegalArgumentException("请选择回放快照");
        return ApiResponse.ok(promptReplayService.replay(id, request.snapshotId()));
    }

    @DeleteMapping("/prompt-replay-snapshots/{id}")
    public ApiResponse<Void> deletePromptReplaySnapshot(@PathVariable long id) {
        promptReplayService.deleteSnapshot(id);
        return ApiResponse.ok(null);
    }

    private PromptStage promptStage(String value) {
        try { return PromptStage.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("不支持的提示词阶段：" + value); }
    }

    private PromptDomain promptDomain(String value) {
        try { return PromptDomain.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("不支持的业务域：" + value); }
    }
}
