package com.larkconnect.agent.mcp;

import java.util.List;
import java.util.Map;

public interface McpQueryGateway {
    QueryContext loadContext(String openId, String chatId, String chatType);
    default QueryContext loadContext(String requestId, String openId, String chatId, String chatType) {
        return loadContext(openId, chatId, chatType);
    }
    default QueryContext loadContext(String requestId, String openId, String chatId, String chatType,
                                     List<String> projectIds) {
        return loadContext(requestId, openId, chatId, chatType);
    }
    List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                  List<String> projectIds, Map<String, Object> arguments);
    default List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                          List<String> projectIds, Map<String, Object> arguments,
                                          ExecutionTrace trace) {
        return execute(requestId, context, toolName, projectIds, arguments);
    }

    record Project(String projectId, String projectName, String projectRemark) {
        public Project(String projectId, String projectName) { this(projectId, projectName, ""); }
    }
    record QueryContext(String primelayerUserId, List<Project> projects,
                        List<Map<String, Object>> availableTools, String availabilityError,
                        List<String> traceEventIds) {
        public QueryContext(String primelayerUserId, List<Project> projects,
                            List<Map<String, Object>> availableTools, String availabilityError) {
            this(primelayerUserId, projects, availableTools, availabilityError, List.of());
        }
    }
    record ExecutionTrace(String parentEventId, List<String> dependencyEventIds,
                          Integer roundIndex, String logicalCallId, Map<String, Object> metadata) {
        public ExecutionTrace {
            dependencyEventIds = dependencyEventIds == null ? List.of() : List.copyOf(dependencyEventIds);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        public ExecutionTrace(String parentEventId, List<String> dependencyEventIds,
                              Integer roundIndex, String logicalCallId) {
            this(parentEventId, dependencyEventIds, roundIndex, logicalCallId, Map.of());
        }
        public static ExecutionTrace empty() { return new ExecutionTrace(null, List.of(), null, null, Map.of()); }
    }
    record ToolObservation(String projectId, String projectName, String toolName, String status,
                           Map<String, Object> payload, String error, int physicalCalls,
                           int pages, boolean truncated, Integer returnedCount,
                           Integer reportedTotalCount, List<String> traceEventIds) {
        public ToolObservation(String projectId, String projectName, String toolName, String status,
                               Map<String, Object> payload, String error, int physicalCalls,
                               int pages, boolean truncated) {
            this(projectId, projectName, toolName, status, payload, error, physicalCalls, pages,
                    truncated, null, null, List.of());
        }
        public boolean succeeded() {
            return "SUCCEEDED".equals(status) || "PARTIAL".equals(status);
        }
    }
}
