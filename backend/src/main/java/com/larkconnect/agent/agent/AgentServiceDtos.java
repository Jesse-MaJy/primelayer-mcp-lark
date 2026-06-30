package com.larkconnect.agent.agent;

import java.util.List;
import java.util.Map;

public final class AgentServiceDtos {
    private AgentServiceDtos() {}

    public record UserContext(String openId, String primelayerUserId) {}

    public record ProjectRef(String projectId, String projectName) {}

    public record AgentAnswerRequest(
            String requestId,
            String question,
            String chatType,
            UserContext userContext,
            List<ProjectRef> projects,
            List<Map<String, Object>> availableTools,
            List<Map<String, Object>> history,
            List<Map<String, Object>> toolResults
    ) {}

    public record ToolCall(
            String toolName,
            Map<String, Object> arguments,
            List<String> projectIds,
            String reason
    ) {}

    public record AgentAnswerResponse(
            Boolean needClarification,
            String clarificationQuestion,
            String skillId,
            String projectScope,
            List<String> projectIds,
            List<ToolCall> toolCalls,
            String answer,
            Map<String, Object> answerMetadata
    ) {
        public boolean requiresClarification() {
            return Boolean.TRUE.equals(needClarification);
        }
    }
}
