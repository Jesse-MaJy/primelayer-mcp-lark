package com.larkconnect.agent.deepseek;

import java.util.List;
import java.util.Map;

public interface DeepSeekConversationClient {
    Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean allowTools);
    default Completion complete(String selectedModel, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools, boolean allowTools) {
        return complete(messages, tools, allowTools);
    }
    default Completion formatPresentation(String selectedModel, List<Map<String, Object>> messages) {
        return complete(selectedModel, messages, List.of(), false);
    }
    String analyzeChunk(String toolName, String projectId, String json, int chunkIndex, int chunkCount);
    default ChunkAnalysis analyzeChunkWithUsage(String selectedModel, String toolName, String projectId,
                                                String json, int chunkIndex, int chunkCount) {
        return new ChunkAnalysis(analyzeChunk(toolName, projectId, json, chunkIndex, chunkCount), 0, 0);
    }
    String model();

    record ToolCall(String id, String name, Map<String, Object> input) {}
    record Completion(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                      int inputTokens, int outputTokens) {}
    record ChunkAnalysis(String content, int inputTokens, int outputTokens) {}
}
