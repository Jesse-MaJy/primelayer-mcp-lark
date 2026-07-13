package com.larkconnect.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Admin admin,
        Agent agent,
        Feishu feishu,
        DeepSeek deepseek,
        Mcp mcp
) {
    public record Admin(long tokenTtlSeconds, String bootstrapUsername, String bootstrapPassword) {}
    public record Agent(int maxProjectsPerQuery, int modelTimeoutMs, int mcpTimeoutMs) {}
    public record Feishu(String appId, String appSecret, String verificationToken, String encryptKey, boolean echoEnabled) {}
    public record DeepSeek(String baseUrl, String apiKey) {}
    public record Mcp(String endpoint, String authHeaderName) {}
}
