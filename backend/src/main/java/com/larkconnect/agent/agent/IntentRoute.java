package com.larkconnect.agent.agent;

public record IntentRoute(
        IntentCategory category,
        String skillId,
        String title,
        String cardTemplate,
        boolean requiresMcp
) {}
