package com.larkconnect.agent.deepseek;

import java.util.List;
import java.util.Map;

public record DeepSeekPlan(
        String intent,
        String projectScope,
        List<String> projectHints,
        List<ToolCall> toolCalls,
        boolean needClarification,
        String clarificationQuestion,
        String answerStyle
) {
    public record ToolCall(String toolName, Map<String, Object> arguments) {}
}
