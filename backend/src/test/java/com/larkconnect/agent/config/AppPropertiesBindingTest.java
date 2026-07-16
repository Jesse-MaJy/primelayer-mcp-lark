package com.larkconnect.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesBindingTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BindingConfiguration.class)
            .withPropertyValues(
                    "app.agent.max-projects-per-query=20",
                    "app.agent.model-timeout-ms=30000",
                    "app.agent.mcp-timeout-ms=30000",
                    "app.agent.query-progress-notice-ms=900000",
                    "app.agent.query-hard-timeout-ms=1800000",
                    "app.agent.query-page-batch-size=5",
                    "app.agent.max-no-progress-decisions=3",
                    "app.agent.model-input-token-budget=256000",
                    "app.agent.async-poll-initial-delay-ms=1000",
                    "app.agent.async-poll-max-delay-ms=30000",
                    "app.agent.form-analysis-timeout-ms=90000",
                    "app.agent.final-answer-timeout-ms=60000",
                    "app.agent.max-planning-rounds=2",
                    "app.agent.max-logical-tool-calls=32",
                    "app.agent.max-stage-planning-calls=5",
                    "app.agent.tool-selection-confidence-threshold=70",
                    "app.agent.form-selection-confidence-threshold=80");

    @Test
    void bindsNestedAgentRecordUsedByHttpClientsAtStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.agent()).isNotNull();
            assertThat(properties.agent().mcpTimeoutMs()).isEqualTo(30_000);
            assertThat(properties.agent().queryPageBatchSize()).isEqualTo(5);
            assertThat(properties.agent().formAnalysisTimeoutMs()).isEqualTo(90_000);
            assertThat(properties.agent().maxPlanningRounds()).isEqualTo(2);
            assertThat(properties.agent().maxStagePlanningCalls()).isEqualTo(5);
            assertThat(properties.agent().toolSelectionConfidenceThreshold()).isEqualTo(70);
            assertThat(properties.agent().formSelectionConfidenceThreshold()).isEqualTo(80);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class BindingConfiguration {}
}
