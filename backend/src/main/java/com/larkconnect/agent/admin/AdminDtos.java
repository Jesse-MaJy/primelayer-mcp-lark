package com.larkconnect.agent.admin;

import jakarta.validation.constraints.NotBlank;

public final class AdminDtos {
    private AdminDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, long expiresInSeconds) {}

    public record UserBindingRequest(
            @NotBlank String feishuOpenId,
            @NotBlank String primelayerUserId,
            String primelayerUserName,
            String status
    ) {}

    public record ProjectTokenRequest(
            @NotBlank String primelayerUserId,
            @NotBlank String projectId,
            @NotBlank String projectName,
            @NotBlank String mcpToken,
            String tokenStatus
    ) {}

    public record ChatProjectBindingRequest(
            @NotBlank String feishuChatId,
            @NotBlank String projectId,
            @NotBlank String projectName,
            String status
    ) {}
}
