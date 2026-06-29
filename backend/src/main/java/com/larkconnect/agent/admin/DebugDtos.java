package com.larkconnect.agent.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public final class DebugDtos {
    private DebugDtos() {}

    public record DeepSeekPlanRequest(
            @NotBlank String question,
            String chatType
    ) {}

    public record DeepSeekSummarizeRequest(
            @NotBlank String question,
            List<Map<String, Object>> toolResults
    ) {}

    public record McpCallRequest(
            @NotNull Long tokenId,
            @NotBlank String toolName,
            Map<String, Object> arguments
    ) {}

    public record FeishuMockEventRequest(
            @NotNull Map<String, Object> event
    ) {}

    public record AgentQueryRequest(
            @NotBlank String feishuOpenId,
            String feishuChatId,
            String chatType,
            @NotBlank String message,
            Boolean sendFeishuMessage
    ) {}
}
