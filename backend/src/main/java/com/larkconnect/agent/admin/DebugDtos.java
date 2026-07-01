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

    public record DeepSeekConnectionRequest(
            String prompt
    ) {}

    public record FastGptConnectionRequest(
            String prompt
    ) {}

    public record McpCallRequest(
            @NotNull Long tokenId,
            @NotBlank String toolName,
            Map<String, Object> arguments
    ) {}

    public record McpToolsRequest(
            @NotNull Long tokenId
    ) {}

    public record McpQuestionRequest(
            @NotNull Long tokenId,
            @NotBlank String question
    ) {}

    public record FeishuMockEventRequest(
            @NotNull Map<String, Object> event
    ) {}

    public record FeishuReplyTestRequest(
            @NotBlank String messageId,
            String text
    ) {}

    public record FeishuCardSendRequest(
            @NotBlank String receiveIdType,
            @NotBlank String receiveId,
            @NotNull Map<String, Object> card
    ) {}

    public record FeishuCardBatchSendRequest(
            @NotBlank String receiveIdType,
            @NotNull List<String> receiveIds,
            @NotNull Map<String, Object> card
    ) {}

    public record AgentQueryRequest(
            @NotBlank String feishuOpenId,
            String feishuChatId,
            String chatType,
            @NotBlank String message,
            Boolean sendFeishuMessage
    ) {}
}
