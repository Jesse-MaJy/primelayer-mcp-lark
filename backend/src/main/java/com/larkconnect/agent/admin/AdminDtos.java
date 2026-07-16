package com.larkconnect.agent.admin;

import jakarta.validation.constraints.NotBlank;

public final class AdminDtos {
    private AdminDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, long expiresInSeconds) {}

    public record UserBindingRequest(
            String personName,
            @NotBlank String feishuOpenId,
            String status
    ) {}

    public record ProjectTokenRequest(
            Long id,
            @NotBlank String feishuOpenId,
            String projectId,
            String projectName,
            String projectRemark,
            String mcpToken,
            String tokenStatus,
            Boolean replaceToken,
            Boolean manualProjectConfirmed
    ) {}

    public record ProjectTokenVerifyRequest(
            @NotBlank String feishuOpenId,
            String mcpToken
    ) {}

    public record AiSettingsRequest(String deepSeekModel) {}

    public record AiSettingsResponse(String deepSeekModel, java.util.List<String> supportedModels) {}

    public record PromptVersionRequest(String content) {}
    public record PromptActionRequest(String note) {}
    public record PromptReplayRequest(Long snapshotId) {}
}
