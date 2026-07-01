package com.larkconnect.agent.ai;

public enum AiEngineType {
    LOCAL_AGENT,
    FASTGPT;

    public static AiEngineType from(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL_AGENT;
        }
        try {
            return AiEngineType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOCAL_AGENT;
        }
    }
}
