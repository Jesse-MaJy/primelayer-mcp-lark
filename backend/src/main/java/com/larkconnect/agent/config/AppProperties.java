package com.larkconnect.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Admin admin,
        Agent agent,
        Feishu feishu,
        DeepSeek deepseek,
        Mcp mcp
) {
    public record Admin(long tokenTtlSeconds, String bootstrapUsername, String bootstrapPassword) {}
    public record Agent(int maxProjectsPerQuery, int modelTimeoutMs, int mcpTimeoutMs,
                        long queryProgressNoticeMs, long queryHardTimeoutMs, int queryPageBatchSize,
                        int maxNoProgressDecisions, int modelInputTokenBudget,
                        int asyncPollInitialDelayMs, int asyncPollMaxDelayMs,
                        int formAnalysisTimeoutMs, int finalAnswerTimeoutMs,
                        int maxPlanningRounds, int maxLogicalToolCalls,
                        int maxCandidateForms, int maxStagePlanningCalls) {
        @ConstructorBinding
        public Agent {}

        public Agent(int maxProjectsPerQuery, int modelTimeoutMs, int mcpTimeoutMs) {
            this(maxProjectsPerQuery, modelTimeoutMs, mcpTimeoutMs,
                    900_000, 1_800_000, 5, 3, 256_000, 1_000, 30_000,
                    90_000, 60_000, 2, 32, 10, 5);
        }
    }
    public record Feishu(String appId, String appSecret, String verificationToken, String encryptKey, boolean echoEnabled) {}
    public record DeepSeek(String baseUrl, String apiKey) {}
    public record Mcp(String endpoint, String authHeaderName) {}
}
