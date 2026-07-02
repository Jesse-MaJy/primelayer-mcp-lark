package com.larkconnect.agent.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
            String reason,
            Map<String, Object> pagination,
            String purpose
    ) {
        @JsonCreator
        public static ToolCall create(
                @JsonProperty("toolName") String toolName,
                @JsonProperty("arguments") Map<String, Object> arguments,
                @JsonProperty("projectIds") List<String> projectIds,
                @JsonProperty("reason") String reason,
                @JsonProperty("pagination") Map<String, Object> pagination,
                @JsonProperty("purpose") String purpose) {
            return new ToolCall(
                    toolName,
                    arguments != null ? arguments : Map.of(),
                    projectIds != null ? projectIds : List.of(),
                    reason,
                    pagination,
                    purpose);
        }
    }

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
