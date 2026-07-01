package com.larkconnect.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekClientTest {
    private final DeepSeekClient client = new DeepSeekClient(
            new AppProperties(
                    new AppProperties.Admin(3600, "admin", "admin"),
                    new AppProperties.Agent(5, 30000, 30000),
                    new AppProperties.AgentService(false, "http://localhost:8000"),
                    new AppProperties.Feishu("", "", "", "", false),
                    new AppProperties.DeepSeek("https://api.deepseek.com", "test-key", "deepseek-chat"),
                    new AppProperties.Mcp("http://localhost/mcp", "X-API-Key")
            ),
            new ObjectMapper(),
            RestClient.builder(),
            new McpToolRegistry()
    );

    @Test
    void parsesJsonCodeBlockAndSnakeCaseFields() throws Exception {
        DeepSeekPlan plan = normalize("""
                ```json
                {
                  "intent": "project_query",
                  "project_scope": "single_project",
                  "project_hints": ["Roche"],
                  "tool_calls": [
                    {
                      "tool_name": "primelayer.query_project_health",
                      "args": {"project": "Roche"}
                    }
                  ],
                  "need_clarification": false,
                  "answer_style": "normal"
                }
                ```
                """);

        assertThat(plan.intent()).isEqualTo("project_query");
        assertThat(plan.projectScope()).isEqualTo("single_project");
        assertThat(plan.projectHints()).containsExactly("Roche");
        assertThat(plan.toolCalls()).hasSize(1);
        assertThat(plan.toolCalls().get(0).toolName()).isEqualTo("primelayer.query_project_health");
        assertThat(plan.toolCalls().get(0).arguments()).containsEntry("question", "今天项目施工情况");
    }

    @Test
    void fallsBackToSafeToolWhenToolNameIsInvalid() throws Exception {
        DeepSeekPlan plan = normalize("""
                {"intent":"project_query","toolCalls":[{"toolName":"delete_project","arguments":{}}]}
                """);

        assertThat(plan.toolCalls()).hasSize(1);
        assertThat(plan.toolCalls().get(0).toolName()).isEqualTo("primelayer.query_project_health");
    }

    private DeepSeekPlan normalize(String rawContent) throws Exception {
        Method method = DeepSeekClient.class.getDeclaredMethod("normalizePlan", String.class, String.class, String.class);
        method.setAccessible(true);
        return (DeepSeekPlan) method.invoke(client, rawContent, "今天项目施工情况", "p2p");
    }
}
