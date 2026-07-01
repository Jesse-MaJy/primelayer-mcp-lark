package com.larkconnect.agent.admin;

import jakarta.validation.constraints.NotBlank;

public final class AdminDtos {
    private AdminDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, long expiresInSeconds) {}

    public record UserBindingRequest(
            String personName,
            @NotBlank String feishuOpenId,
            String primelayerUserId,
            String primelayerUserName,
            String status
    ) {}

    public record ProjectTokenRequest(
            Long id,
            String ownerType,
            String ownerId,
            String primelayerUserId,
            String projectId,
            String projectName,
            String projectRemark,
            String mcpToken,
            String tokenStatus,
            Boolean replaceToken,
            Boolean manualProjectConfirmed
    ) {}

    public record ProjectTokenVerifyRequest(
            String ownerType,
            String ownerId,
            String primelayerUserId,
            String mcpToken
    ) {}

    public record ChatProjectBindingRequest(
            @NotBlank String feishuChatId,
            @NotBlank String projectId,
            @NotBlank String projectName,
            String status
    ) {}

    public record AiSettingsRequest(
            String engine,
            String fastGptBaseUrl,
            String fastGptModel,
            String fastGptApiKey,
            Integer fastGptTimeoutMs,
            Boolean fastGptMemoryEnabled
    ) {}

    public record AiSettingsResponse(
            String engine,
            String fastGptBaseUrl,
            String fastGptModel,
            boolean fastGptApiKeyConfigured,
            int fastGptTimeoutMs,
            boolean fastGptMemoryEnabled
    ) {}
}
