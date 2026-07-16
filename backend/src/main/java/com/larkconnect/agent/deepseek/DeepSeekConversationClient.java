package com.larkconnect.agent.deepseek;

import java.util.List;
import java.util.Map;

public interface DeepSeekConversationClient {
    Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean allowTools);
    default Completion complete(String selectedModel, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools, boolean allowTools) {
        return complete(messages, tools, allowTools);
    }
    default Completion complete(TraceContext traceContext, String selectedModel,
                                List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools, boolean allowTools) {
        return complete(selectedModel, messages, tools, allowTools);
    }
    default Completion completeStructured(TraceContext traceContext, String selectedModel,
                                          List<Map<String, Object>> messages) {
        return complete(traceContext, selectedModel, messages, List.of(), false);
    }
    default Completion formatPresentation(String selectedModel, List<Map<String, Object>> messages) {
        return complete(selectedModel, messages, List.of(), false);
    }
    default Completion formatPresentation(TraceContext traceContext, String selectedModel,
                                          List<Map<String, Object>> messages) {
        return formatPresentation(selectedModel, messages);
    }
    default ChunkAnalysis analyzeFormWithUsage(TraceContext traceContext, String selectedModel,
                                               String formId, String formName, String compactJson) {
        return analyzeChunkWithUsage(traceContext, selectedModel, "form_data", formId,
                compactJson, 1, 1);
    }
    default Completion finalizeAnswer(TraceContext traceContext, String selectedModel,
                                      List<Map<String, Object>> messages) {
        return complete(traceContext, selectedModel, messages, List.of(), false);
    }
    String analyzeChunk(String toolName, String projectId, String json, int chunkIndex, int chunkCount);
    default ChunkAnalysis analyzeChunkWithUsage(String selectedModel, String toolName, String projectId,
                                                String json, int chunkIndex, int chunkCount) {
        return new ChunkAnalysis(analyzeChunk(toolName, projectId, json, chunkIndex, chunkCount), 0, 0);
    }
    default ChunkAnalysis analyzeChunkWithUsage(TraceContext traceContext, String selectedModel,
                                                String toolName, String projectId, String json,
                                                int chunkIndex, int chunkCount) {
        return analyzeChunkWithUsage(selectedModel, toolName, projectId, json, chunkIndex, chunkCount);
    }
    String model();

    record ToolCall(String id, String name, Map<String, Object> input) {}
    record TraceContext(String requestId, String parentEventId, List<String> dependencyEventIds,
                        Integer roundIndex, String purpose, String label, Map<String, Object> metadata) {
        public TraceContext {
            dependencyEventIds = dependencyEventIds == null ? List.of() : List.copyOf(dependencyEventIds);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
        public TraceContext(String requestId, String parentEventId, List<String> dependencyEventIds,
                            Integer roundIndex, String purpose, String label) {
            this(requestId, parentEventId, dependencyEventIds, roundIndex, purpose, label, Map.of());
        }
    }
    record Completion(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                      int inputTokens, int outputTokens, String traceEventId, long latencyMs,
                      Map<String, Object> usage) {
        public Completion(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                          int inputTokens, int outputTokens) {
            this(content, toolCalls, assistantMessage, inputTokens, outputTokens, null, 0, Map.of());
        }
    }
    record ChunkAnalysis(String content, int inputTokens, int outputTokens, String traceEventId) {
        public ChunkAnalysis(String content, int inputTokens, int outputTokens) {
            this(content, inputTokens, outputTokens, null);
        }
    }
}
