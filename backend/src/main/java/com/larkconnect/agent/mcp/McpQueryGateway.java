package com.larkconnect.agent.mcp;

import java.util.List;
import java.util.Map;

public interface McpQueryGateway {
    QueryContext loadContext(String openId, String chatId, String chatType);
    List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                  List<String> projectIds, Map<String, Object> arguments);

    record Project(String projectId, String projectName) {}
    record QueryContext(String primelayerUserId, List<Project> projects,
                        List<Map<String, Object>> availableTools, String availabilityError) {}
    record ToolObservation(String projectId, String projectName, String toolName, String status,
                           Map<String, Object> payload, String error, int physicalCalls,
                           int pages, boolean truncated) {
        public boolean succeeded() {
            return "SUCCEEDED".equals(status) || "PARTIAL".equals(status);
        }
    }
}
