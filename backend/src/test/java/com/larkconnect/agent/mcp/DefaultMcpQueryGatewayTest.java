package com.larkconnect.agent.mcp;

import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMcpQueryGatewayTest {
    @Test
    void exposesProjectSpecificAliasesWhenCandidateProjectsUseDifferentSchemas() {
        TokenResolver resolver = mock(TokenResolver.class);
        McpAdapter adapter = mock(McpAdapter.class);
        when(resolver.resolveCandidates("u1", "c1", "p2p", 20)).thenReturn(TokenResolver.ResolvedContext.ok("pl", List.of(
                new TokenResolver.TokenEntry(1L, "P1", "一", null, "token-1"),
                new TokenResolver.TokenEntry(2L, "P2", "二", null, "token-2"))));
        when(adapter.listTools("token-1")).thenReturn(toolList("string"));
        when(adapter.listTools("token-2")).thenReturn(toolList("integer"));

        McpQueryGateway.QueryContext context = gateway(resolver, adapter).loadContext("u1", "c1", "p2p");

        assertThat(context.availableTools()).hasSize(2);
        assertThat(context.availableTools()).extracting(tool -> tool.get("supportedProjectIds"))
                .containsExactly(List.of("P1"), List.of("P2"));
        assertThat(context.availabilityError()).isNull();
    }

    private DefaultMcpQueryGateway gateway(TokenResolver resolver, McpAdapter adapter) {
        AppProperties properties = new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(20, 30000, 30000),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("https://api.deepseek.com", "key"),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key"));
        return new DefaultMcpQueryGateway(resolver, adapter, new McpToolRegistry(), mock(AuditService.class), properties);
    }

    private Map<String, Object> toolList(String statusType) {
        return Map.of("result", Map.of("tools", List.of(Map.of(
                "name", "query_tasks",
                "inputSchema", Map.of("type", "object", "properties", Map.of(
                        "status", Map.of("type", statusType)))))));
    }
}
