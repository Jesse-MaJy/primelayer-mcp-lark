# MCP Chain Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the confirmed C方案: Java-led DeepSeek MCP planning, deterministic MCP pagination, redacted trace persistence, and admin trace detail viewing.

**Architecture:** Java remains the security and orchestration boundary. DeepSeek returns structured multi-tool plans and final summaries, while new Java runner/services execute MCP calls, paginate list results, redact payloads, and persist trace events. Admin Web reads trace events by `requestId` and renders a readable timeline with expandable redacted JSON.

**Tech Stack:** Java 17, Spring Boot, JdbcTemplate, Flyway, JUnit 5, AssertJ, Vue 3, TypeScript, Ant Design Vue, Vite.

---

## File Structure

- Create `backend/src/main/resources/db/migration/V6__add_agent_trace_event.sql`
  - Adds the dedicated trace event table and indexes.
- Create `backend/src/main/java/com/larkconnect/agent/audit/TraceEventService.java`
  - Redacts JSON payloads and persists trace events.
- Create `backend/src/test/java/com/larkconnect/agent/audit/TraceEventServiceTest.java`
  - Verifies recursive redaction and payload truncation behavior.
- Create `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekToolPlan.java`
  - Defines the new multi-tool plan contract.
- Modify `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekClient.java`
  - Adds structured MCP planning, follow-up decision, and trace-friendly summary prompts.
- Modify `backend/src/test/java/com/larkconnect/agent/deepseek/DeepSeekClientTest.java`
  - Verifies multi-tool plan parsing, invalid tool fallback/rejection behavior, and JSON code block handling.
- Create `backend/src/main/java/com/larkconnect/agent/mcp/McpExecutionRunner.java`
  - Executes project/tool/page loops, validates tools, compacts results, and writes trace events.
- Create `backend/src/test/java/com/larkconnect/agent/mcp/McpExecutionRunnerTest.java`
  - Verifies cursor pagination, page-number pagination, no-pagination single page behavior, and partial failures.
- Modify `backend/src/main/java/com/larkconnect/agent/config/AppProperties.java`
  - Adds configurable limits for decision rounds, pages, items, trace bytes, and summary payload bytes.
- Modify `backend/src/main/resources/application.yml`
  - Adds default limit values.
- Modify `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java`
  - Integrates Java-led runner for real Feishu MCP flow while preserving FastGPT and config-status branches.
- Modify `backend/src/test/java/com/larkconnect/agent/agent/AgentOrchestratorTest.java`
  - Verifies real flow writes trace and handles multi-tool results.
- Modify `backend/src/main/java/com/larkconnect/agent/admin/AdminDtos.java`
  - Adds DTOs for audit detail and trace event responses.
- Modify `backend/src/main/java/com/larkconnect/agent/admin/AdminRepository.java`
  - Adds query methods for audit detail, agent task, and trace events.
- Modify `backend/src/main/java/com/larkconnect/agent/admin/AdminService.java`
  - Adds `auditLogDetail(requestId)`.
- Modify `backend/src/main/java/com/larkconnect/agent/admin/AdminController.java`
  - Adds `GET /api/admin/audit-logs/{requestId}`.
- Modify `backend/src/main/java/com/larkconnect/agent/admin/DebugService.java`
  - Makes agent debug execution return `requestId` and pass that ID into the same Java MCP runner used by the Feishu flow.
- Modify `admin-web/src/api/admin.ts`
  - Adds `getAuditLogDetail(requestId)`.
- Modify `admin-web/src/router/index.ts`
  - Adds `/audit-logs/:requestId`.
- Modify `admin-web/src/views/AuditLogsView.vue`
  - Adds a detail action column.
- Create `admin-web/src/views/AuditLogDetailView.vue`
  - Renders the trace overview, timeline, and expandable redacted JSON.

---

### Task 1: Add Trace Event Persistence And Redaction

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__add_agent_trace_event.sql`
- Create: `backend/src/main/java/com/larkconnect/agent/audit/TraceEventService.java`
- Create: `backend/src/test/java/com/larkconnect/agent/audit/TraceEventServiceTest.java`

- [ ] **Step 1: Write the Flyway migration**

Create `backend/src/main/resources/db/migration/V6__add_agent_trace_event.sql`:

```sql
CREATE TABLE agent_trace_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  step_index INT NOT NULL,
  round_index INT NOT NULL DEFAULT 0,
  parent_step_id BIGINT NULL,
  event_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  summary TEXT,
  input_json JSON,
  output_json JSON,
  error_json JSON,
  project_id VARCHAR(128),
  project_name VARCHAR(256),
  primelayer_user_id VARCHAR(128),
  tool_name VARCHAR(128),
  page_info JSON,
  latency_ms BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_trace_request_step (request_id, step_index),
  INDEX idx_trace_created_at (created_at),
  INDEX idx_trace_request_event (request_id, event_type)
);
```

- [ ] **Step 2: Write failing redaction tests**

Create `backend/src/test/java/com/larkconnect/agent/audit/TraceEventServiceTest.java`:

```java
package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceEventServiceTest {
    private final TraceEventService service = new TraceEventService(null, new ObjectMapper());

    @Test
    void redactsSensitiveKeysRecursively() {
        Map<String, Object> input = Map.of(
                "token", "secret-token",
                "nested", Map.of("Authorization", "Bearer abc", "safe", "visible"),
                "items", List.of(Map.of("apiKey", "deepseek-key", "name", "Roche"))
        );

        Object redacted = service.redactForTest(input);

        assertThat(redacted.toString()).contains("[REDACTED]");
        assertThat(redacted.toString()).doesNotContain("secret-token");
        assertThat(redacted.toString()).doesNotContain("Bearer abc");
        assertThat(redacted.toString()).doesNotContain("deepseek-key");
        assertThat(redacted.toString()).contains("visible");
        assertThat(redacted.toString()).contains("Roche");
    }

    @Test
    void compactsLargeListPayloadsWithTruncationMetadata() {
        List<Map<String, Object>> items = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> Map.of("id", "task-" + i, "name", "Task " + i))
                .toList();

        Object compacted = service.compactForTest(Map.of("items", items), 600);

        String text = compacted.toString();
        assertThat(text).contains("truncated=true");
        assertThat(text).contains("itemCount=20");
        assertThat(text).contains("task-0");
    }
}
```

- [ ] **Step 3: Run the failing test**

Run:

```bash
cd backend
mvn -Dtest=TraceEventServiceTest test
```

Expected: compile fails because `TraceEventService` does not exist.

- [ ] **Step 4: Implement `TraceEventService`**

Create `backend/src/main/java/com/larkconnect/agent/audit/TraceEventService.java`:

```java
package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.common.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TraceEventService {
    private static final String REDACTED = "[REDACTED]";
    private static final int DEFAULT_MAX_BYTES = 64_000;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicInteger fallbackStepCounter = new AtomicInteger();

    public TraceEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void write(TraceEvent event) {
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("""
                insert into agent_trace_event(request_id, step_index, round_index, parent_step_id, event_type, status,
                  summary, input_json, output_json, error_json, project_id, project_name, primelayer_user_id,
                  tool_name, page_info, latency_ms)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as json), cast(? as json), cast(? as json), ?, ?, ?, ?, cast(? as json), ?)
                """,
                event.requestId(),
                event.stepIndex() == null ? fallbackStepCounter.incrementAndGet() : event.stepIndex(),
                event.roundIndex() == null ? 0 : event.roundIndex(),
                event.parentStepId(),
                event.eventType(),
                event.status(),
                event.summary(),
                toJson(redactAndCompact(event.input(), DEFAULT_MAX_BYTES)),
                toJson(redactAndCompact(event.output(), DEFAULT_MAX_BYTES)),
                toJson(redactAndCompact(event.error(), DEFAULT_MAX_BYTES)),
                event.projectId(),
                event.projectName(),
                event.primelayerUserId(),
                event.toolName(),
                toJson(redactAndCompact(event.pageInfo(), DEFAULT_MAX_BYTES)),
                event.latencyMs());
    }

    Object redactForTest(Object value) {
        return redact(value);
    }

    Object compactForTest(Object value, int maxBytes) {
        return redactAndCompact(value, maxBytes);
    }

    private Object redactAndCompact(Object value, int maxBytes) {
        Object redacted = redact(value);
        String json = toJson(redacted);
        if (json.length() <= maxBytes) {
            return redacted;
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("truncated", true);
        compact.put("originalSizeBytes", json.length());
        compact.put("retainedSizeBytes", maxBytes);
        compact.put("sample", sample(redacted));
        return compact;
    }

    @SuppressWarnings("unchecked")
    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                result.put(key, isSensitiveKey(key) ? REDACTED : redact(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redact).toList();
        }
        return value;
    }

    private Object sample(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sample = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object item = entry.getValue();
                if (item instanceof List<?> list) {
                    sample.put(String.valueOf(entry.getKey()), list.stream().limit(3).toList());
                    sample.put("itemCount", list.size());
                } else {
                    sample.put(String.valueOf(entry.getKey()), item);
                }
            }
            return sample;
        }
        if (value instanceof List<?> list) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("items", list.stream().limit(3).toList());
            sample.put("itemCount", list.size());
            return sample;
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.equals("auth")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("ciphertext");
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public record TraceEvent(
            String requestId,
            Integer stepIndex,
            Integer roundIndex,
            Long parentStepId,
            String eventType,
            String status,
            String summary,
            Object input,
            Object output,
            Object error,
            String projectId,
            String projectName,
            String primelayerUserId,
            String toolName,
            Object pageInfo,
            Long latencyMs
    ) {
        public static TraceEvent succeeded(String requestId, int stepIndex, int roundIndex, String eventType, String summary, Object input, Object output) {
            return new TraceEvent(requestId, stepIndex, roundIndex, null, eventType, Status.SUCCEEDED, summary, input, output, null, null, null, null, null, null, 0L);
        }
    }
}
```

- [ ] **Step 5: Run the redaction test**

Run:

```bash
cd backend
mvn -Dtest=TraceEventServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add backend/src/main/resources/db/migration/V6__add_agent_trace_event.sql backend/src/main/java/com/larkconnect/agent/audit/TraceEventService.java backend/src/test/java/com/larkconnect/agent/audit/TraceEventServiceTest.java
git commit -m "feat: add agent trace event persistence"
```

---

### Task 2: Add DeepSeek Multi-Tool Planning Contract

**Files:**
- Create: `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekToolPlan.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekClient.java`
- Modify: `backend/src/test/java/com/larkconnect/agent/deepseek/DeepSeekClientTest.java`

- [ ] **Step 1: Write failing DeepSeek plan parsing tests**

Append these tests to `backend/src/test/java/com/larkconnect/agent/deepseek/DeepSeekClientTest.java`:

```java
@Test
void parsesMultiToolMcpPlan() throws Exception {
    Method method = DeepSeekClient.class.getDeclaredMethod("normalizeToolPlan", String.class, String.class, java.util.List.class);
    method.setAccessible(true);
    java.util.List<java.util.Map<String, Object>> tools = java.util.List.of(
            java.util.Map.of("name", "primelayer.query_tasks", "inputSchema", java.util.Map.of("type", "object")),
            java.util.Map.of("name", "primelayer.query_project_health", "inputSchema", java.util.Map.of("type", "object"))
    );

    DeepSeekToolPlan plan = (DeepSeekToolPlan) method.invoke(client, """
            {
              "projectScope": "all_accessible_projects",
              "projectIds": ["p1", "p2"],
              "toolCalls": [
                {"toolName":"primelayer.query_tasks","arguments":{"status":"overdue"},"projectIds":["p1"],"pagination":{"mode":"auto"},"reason":"tasks"},
                {"toolName":"primelayer.query_project_health","arguments":{},"projectIds":["p1","p2"],"reason":"health"}
              ],
              "finalAnswerReady": false
            }
            """, "查逾期风险", tools);

    assertThat(plan.toolCalls()).hasSize(2);
    assertThat(plan.toolCalls().get(0).toolName()).isEqualTo("primelayer.query_tasks");
    assertThat(plan.toolCalls().get(0).arguments()).containsEntry("status", "overdue");
    assertThat(plan.toolCalls().get(0).pagination()).containsEntry("mode", "auto");
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cd backend
mvn -Dtest=DeepSeekClientTest test
```

Expected: compile fails because `DeepSeekToolPlan` and `normalizeToolPlan` do not exist.

- [ ] **Step 3: Create `DeepSeekToolPlan`**

Create `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekToolPlan.java`:

```java
package com.larkconnect.agent.deepseek;

import java.util.List;
import java.util.Map;

public record DeepSeekToolPlan(
        boolean needClarification,
        String clarificationQuestion,
        String projectScope,
        List<String> projectIds,
        List<ToolCall> toolCalls,
        boolean finalAnswerReady,
        String answer
) {
    public record ToolCall(
            String toolName,
            Map<String, Object> arguments,
            List<String> projectIds,
            Map<String, Object> pagination,
            String reason
    ) {}
}
```

- [ ] **Step 4: Add normalization methods to `DeepSeekClient`**

Modify `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekClient.java`:

```java
public DeepSeekToolPlan planMcpToolCalls(String requestId, String question, String chatType, List<Map<String, Object>> tools, List<Map<String, Object>> priorResults) {
    if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
        return fallbackToolPlan(question, tools);
    }
    try {
        String prompt = """
                你是 Agent Gateway 的 MCP 工具规划器。只能输出 JSON。
                必须返回字段：needClarification, clarificationQuestion, projectScope, projectIds, toolCalls, finalAnswerReady, answer。
                toolCalls 必须是数组，可以包含多个 MCP 工具。
                toolName 必须来自可用工具。不要输出 token、Authorization、密钥或认证字段。
                如果已有工具结果足够回答，设置 finalAnswerReady=true 并输出 answer。

                用户问题：%s
                可用工具：%s
                已有工具结果摘要：%s
                """.formatted(question, objectMapper.writeValueAsString(tools), objectMapper.writeValueAsString(priorResults));
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(Map.of(
                        "model", properties.deepseek().model(),
                        "messages", List.of(Map.of("role", "user", "content", prompt)),
                        "response_format", Map.of("type", "json_object"),
                        "temperature", 0
                ))
                .retrieve()
                .body(Map.class);
        JsonNode content = objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content");
        return normalizeToolPlan(content.asText(), question, tools);
    } catch (Exception e) {
        return fallbackToolPlan(question, tools);
    }
}
```

Also add private `normalizeToolPlan(...)`, `normalizeToolPlanCalls(...)`, and `fallbackToolPlan(...)` methods. Reuse existing helpers `extractJsonObject`, `text`, `textList`, `bool`, and `hasText`; when a tool name is not in `tools`, skip that call instead of inventing a mutating tool.

- [ ] **Step 5: Run DeepSeek tests**

Run:

```bash
cd backend
mvn -Dtest=DeepSeekClientTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekToolPlan.java backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekClient.java backend/src/test/java/com/larkconnect/agent/deepseek/DeepSeekClientTest.java
git commit -m "feat: parse deepseek multi tool plans"
```

---

### Task 3: Add MCP Execution Runner With Pagination

**Files:**
- Create: `backend/src/main/java/com/larkconnect/agent/mcp/McpExecutionRunner.java`
- Create: `backend/src/test/java/com/larkconnect/agent/mcp/McpExecutionRunnerTest.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/config/AppProperties.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Write failing pagination tests**

Create `backend/src/test/java/com/larkconnect/agent/mcp/McpExecutionRunnerTest.java`:

```java
package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.audit.TraceEventService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.deepseek.DeepSeekToolPlan;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpExecutionRunnerTest {
    @Test
    void followsCursorPaginationUntilNoNextCursor() {
        FakeMcpAdapter adapter = new FakeMcpAdapter();
        adapter.enqueue(Map.of("result", Map.of("items", List.of("a"), "nextCursor", "c2", "hasMore", true)));
        adapter.enqueue(Map.of("result", Map.of("items", List.of("b"), "hasMore", false)));
        McpExecutionRunner runner = runner(adapter);

        List<Map<String, Object>> results = runner.execute("req-1", "pl-user", List.of(token()), List.of(call()), tools());

        assertThat(adapter.arguments).hasSize(2);
        assertThat(adapter.arguments.get(1)).containsEntry("cursor", "c2");
        assertThat(results).hasSize(1);
        assertThat(String.valueOf(results.get(0))).contains("pageCount=2");
        assertThat(String.valueOf(results.get(0))).contains("itemCount=2");
    }

    @Test
    void treatsNonPagedResponseAsSinglePage() {
        FakeMcpAdapter adapter = new FakeMcpAdapter();
        adapter.enqueue(Map.of("result", Map.of("items", List.of("only"))));

        List<Map<String, Object>> results = runner(adapter).execute("req-2", "pl-user", List.of(token()), List.of(call()), tools());

        assertThat(adapter.arguments).hasSize(1);
        assertThat(String.valueOf(results.get(0))).contains("pageCount=1");
    }

    private McpExecutionRunner runner(FakeMcpAdapter adapter) {
        return new McpExecutionRunner(adapter, new McpToolRegistry(), new TraceEventService(null, new ObjectMapper()), limits());
    }

    private AppProperties.Agent limits() {
        return new AppProperties.Agent(5, 30000, 30000, 4, 20, 1000, 64000, 120000);
    }

    private TokenResolver.TokenEntry token() {
        return new TokenResolver.TokenEntry(1L, "p1", "Project 1", "Project 1", "token");
    }

    private DeepSeekToolPlan.ToolCall call() {
        return new DeepSeekToolPlan.ToolCall("primelayer.query_tasks", new LinkedHashMap<>(), List.of("p1"), Map.of("mode", "auto"), "tasks");
    }

    private List<Map<String, Object>> tools() {
        return List.of(Map.of("name", "primelayer.query_tasks", "inputSchema", Map.of("type", "object", "properties", Map.of())));
    }

    static class FakeMcpAdapter extends McpAdapter {
        final List<Map<String, Object>> queued = new ArrayList<>();
        final List<Map<String, Object>> arguments = new ArrayList<>();

        FakeMcpAdapter() {
            super(null, null, null);
        }

        void enqueue(Map<String, Object> value) {
            queued.add(value);
        }

        @Override
        public Map<String, Object> callTool(String token, String toolName, Map<String, Object> args) {
            arguments.add(new LinkedHashMap<>(args));
            return queued.remove(0);
        }
    }
}
```

- [ ] **Step 2: Run the failing runner test**

Run:

```bash
cd backend
mvn -Dtest=McpExecutionRunnerTest test
```

Expected: compile fails because `McpExecutionRunner` does not exist.

- [ ] **Step 3: Add configurable limits**

Modify `backend/src/main/java/com/larkconnect/agent/config/AppProperties.java` by extending the `Agent` record:

```java
public record Agent(
        int maxProjectsPerQuery,
        int modelTimeoutMs,
        int mcpTimeoutMs,
        int maxDecisionRounds,
        int maxPagesPerTool,
        int maxItemsPerTool,
        int maxTracePayloadBytes,
        int maxSummaryPayloadBytes
) {}
```

Update all existing `new AppProperties.Agent(...)` test constructors to pass defaults:

```java
new AppProperties.Agent(5, 30000, 30000, 4, 20, 1000, 64000, 120000)
```

Modify `backend/src/main/resources/application.yml`:

```yaml
app:
  agent:
    max-decision-rounds: ${AGENT_MAX_DECISION_ROUNDS:4}
    max-pages-per-tool: ${AGENT_MAX_PAGES_PER_TOOL:20}
    max-items-per-tool: ${AGENT_MAX_ITEMS_PER_TOOL:1000}
    max-trace-payload-bytes: ${AGENT_MAX_TRACE_PAYLOAD_BYTES:64000}
    max-summary-payload-bytes: ${AGENT_MAX_SUMMARY_PAYLOAD_BYTES:120000}
```

- [ ] **Step 4: Implement `McpExecutionRunner`**

Create `backend/src/main/java/com/larkconnect/agent/mcp/McpExecutionRunner.java`:

```java
package com.larkconnect.agent.mcp;

import com.larkconnect.agent.audit.TraceEventService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.deepseek.DeepSeekToolPlan;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpExecutionRunner {
    private final McpAdapter mcpAdapter;
    private final McpToolRegistry toolRegistry;
    private final TraceEventService traceEventService;
    private final AppProperties.Agent limits;

    public McpExecutionRunner(McpAdapter mcpAdapter, McpToolRegistry toolRegistry, TraceEventService traceEventService, AppProperties properties) {
        this(mcpAdapter, toolRegistry, traceEventService, properties.agent());
    }

    McpExecutionRunner(McpAdapter mcpAdapter, McpToolRegistry toolRegistry, TraceEventService traceEventService, AppProperties.Agent limits) {
        this.mcpAdapter = mcpAdapter;
        this.toolRegistry = toolRegistry;
        this.traceEventService = traceEventService;
        this.limits = limits;
    }

    public List<Map<String, Object>> execute(String requestId, String primelayerUserId, List<TokenResolver.TokenEntry> tokens, List<DeepSeekToolPlan.ToolCall> calls, List<Map<String, Object>> availableTools) {
        List<Map<String, Object>> results = new ArrayList<>();
        int step = 100;
        for (DeepSeekToolPlan.ToolCall call : calls) {
            for (TokenResolver.TokenEntry token : targetTokens(tokens, call.projectIds())) {
                results.add(executeOne(requestId, step++, primelayerUserId, token, call, availableTools));
            }
        }
        return results;
    }

    private Map<String, Object> executeOne(String requestId, int step, String primelayerUserId, TokenResolver.TokenEntry token, DeepSeekToolPlan.ToolCall call, List<Map<String, Object>> availableTools) {
        long started = System.currentTimeMillis();
        Map<String, Object> args = new LinkedHashMap<>(call.arguments() == null ? Map.of() : call.arguments());
        args.put("project_id", token.projectId());
        args.put("primelayer_user_id", primelayerUserId);
        List<Object> pages = new ArrayList<>();
        try {
            toolRegistry.validate(call.toolName(), args, availableTools);
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            for (int page = 1; page <= limits.maxPagesPerTool(); page++) {
                Map<String, Object> response = mcpAdapter.callTool(token.token(), call.toolName(), args);
                pages.add(response);
                pageInfo = nextPageInfo(response, page);
                writePageTrace(requestId, step + page - 1, call, token, args, response, pageInfo, started, Status.SUCCEEDED, null);
                Object nextCursor = pageInfo.get("nextCursor");
                if (nextCursor != null) {
                    args.put("cursor", nextCursor);
                    continue;
                }
                if (Boolean.TRUE.equals(pageInfo.get("hasMore")) && pageInfo.get("nextPage") != null) {
                    args.put("page", pageInfo.get("nextPage"));
                    continue;
                }
                break;
            }
            return result(token, call, Status.SUCCEEDED, pages, null);
        } catch (Exception e) {
            writePageTrace(requestId, step, call, token, args, null, Map.of(), started, Status.FAILED, e.getMessage());
            return result(token, call, Status.FAILED, pages, e.getMessage());
        }
    }

    private void writePageTrace(String requestId, int step, DeepSeekToolPlan.ToolCall call, TokenResolver.TokenEntry token, Map<String, Object> args, Object output, Object pageInfo, long started, String status, String error) {
        traceEventService.write(new TraceEventService.TraceEvent(
                requestId, step, 0, null, "mcp.call", status,
                call.toolName() + " / " + token.projectName(),
                args, output, error == null ? null : Map.of("message", error),
                token.projectId(), token.projectName(), null, call.toolName(), pageInfo,
                System.currentTimeMillis() - started
        ));
    }

    private Map<String, Object> result(TokenResolver.TokenEntry token, DeepSeekToolPlan.ToolCall call, String status, List<Object> pages, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", token.projectId());
        result.put("projectName", token.projectName());
        result.put("toolName", call.toolName());
        result.put("status", status);
        result.put("pageCount", pages.size());
        result.put("itemCount", pages.stream().mapToInt(this::countItems).sum());
        result.put("pages", pages);
        if (error != null) {
            result.put("error", error);
        }
        return result;
    }

    private Map<String, Object> nextPageInfo(Object response, int currentPage) {
        Map<String, Object> info = new LinkedHashMap<>();
        Object nextCursor = find(response, "nextCursor", "next_cursor", "cursor");
        if (nextCursor != null && !String.valueOf(nextCursor).isBlank()) {
            info.put("nextCursor", nextCursor);
        }
        Object hasMore = find(response, "hasMore", "has_more", "more");
        if (hasMore instanceof Boolean bool) {
            info.put("hasMore", bool);
        }
        if (Boolean.TRUE.equals(info.get("hasMore"))) {
            info.put("nextPage", currentPage + 1);
        }
        info.put("page", currentPage);
        return info;
    }

    private int countItems(Object response) {
        Object items = find(response, "items", "records", "list", "data");
        return items instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private Object find(Object node, String... keys) {
        if (node instanceof Map<?, ?> map) {
            for (String key : keys) {
                if (map.containsKey(key)) {
                    return map.get(key);
                }
            }
            for (Object value : map.values()) {
                Object found = find(value, keys);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<TokenResolver.TokenEntry> targetTokens(List<TokenResolver.TokenEntry> tokens, List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return tokens;
        }
        Set<String> ids = Set.copyOf(projectIds);
        return tokens.stream().filter(token -> ids.contains(token.projectId())).toList();
    }
}
```

- [ ] **Step 5: Run runner tests**

Run:

```bash
cd backend
mvn -Dtest=McpExecutionRunnerTest test
```

Expected: PASS.

- [ ] **Step 6: Run affected backend tests**

Run:

```bash
cd backend
mvn -Dtest=AgentOrchestratorTest,DeepSeekClientTest,McpExecutionRunnerTest,TraceEventServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git add backend/src/main/java/com/larkconnect/agent/mcp/McpExecutionRunner.java backend/src/test/java/com/larkconnect/agent/mcp/McpExecutionRunnerTest.java backend/src/main/java/com/larkconnect/agent/config/AppProperties.java backend/src/main/resources/application.yml backend/src/test/java
git commit -m "feat: execute paged mcp tool plans"
```

---

### Task 4: Integrate Java-Led Runner Into Real Agent Flow

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java`
- Modify: `backend/src/test/java/com/larkconnect/agent/agent/AgentOrchestratorTest.java`

- [ ] **Step 1: Write failing orchestrator test**

Append to `AgentOrchestratorTest`:

```java
@Test
void localJavaFlowExecutesMultipleDeepSeekToolCallsAndSummarizes() {
    FakeTaskService taskService = new FakeTaskService(task("帮我分析 Roche 项目的逾期待办和健康度"));
    FakeTokenResolver tokenResolver = new FakeTokenResolver(TokenResolver.ResolvedContext.ok("pl-user", List.of(token())));
    FakeMcpAdapter mcpAdapter = new FakeMcpAdapter();
    mcpAdapter.tools = tools("primelayer.query_tasks", "primelayer.query_project_health");
    mcpAdapter.results.put("primelayer.query_tasks", Map.of("result", Map.of("items", List.of("逾期任务"))));
    mcpAdapter.results.put("primelayer.query_project_health", Map.of("result", Map.of("items", List.of("健康度一般"))));
    FakeFeishuClient feishuClient = new FakeFeishuClient();
    FakeDeepSeekClient deepSeekClient = new FakeDeepSeekClient();
    deepSeekClient.toolPlan = new DeepSeekToolPlan(false, null, "single_project", List.of("Roche"), List.of(
            new DeepSeekToolPlan.ToolCall("primelayer.query_tasks", Map.of(), List.of("Roche"), Map.of("mode", "auto"), "tasks"),
            new DeepSeekToolPlan.ToolCall("primelayer.query_project_health", Map.of(), List.of("Roche"), Map.of("mode", "auto"), "health")
    ), false, null);
    deepSeekClient.summaryAnswer = "逾期和健康度分析完成";

    orchestrator(taskService, deepSeekClient, tokenResolver, mcpAdapter, feishuClient, new FakeAgentServiceClient()).process("req-java");

    assertThat(mcpAdapter.calledTools).containsExactly("primelayer.query_tasks", "primelayer.query_project_health");
    assertThat(feishuClient.lastAnswer).isEqualTo("逾期和健康度分析完成");
}
```

Update `FakeDeepSeekClient` in the same test file with overridable `planMcpToolCalls(...)` and summary answer fields.

- [ ] **Step 2: Run failing orchestrator test**

Run:

```bash
cd backend
mvn -Dtest=AgentOrchestratorTest#localJavaFlowExecutesMultipleDeepSeekToolCallsAndSummarizes test
```

Expected: compile fails until `AgentOrchestrator` accepts/invokes `McpExecutionRunner` and fake client methods exist.

- [ ] **Step 3: Inject `McpExecutionRunner` and `TraceEventService`**

Modify `AgentOrchestrator` constructor to include:

```java
private final McpExecutionRunner mcpExecutionRunner;
private final TraceEventService traceEventService;
```

Add constructor parameters after `McpAdapter mcpAdapter`:

```java
McpExecutionRunner mcpExecutionRunner,
TraceEventService traceEventService,
```

Update all `new AgentOrchestrator(...)` calls in tests to pass a real runner with fake adapter:

```java
TraceEventService trace = new TraceEventService(null, new ObjectMapper());
new McpExecutionRunner(mcpAdapter, new McpToolRegistry(), trace, properties().agent())
```

- [ ] **Step 4: Add Java-led MCP processing branch**

In `process(...)`, after context resolution and before `processWithAgentService(...)`, use Java-led flow when local engine is selected:

```java
if (route.requiresMcp()) {
    processWithJavaMcpRunner(requestId, question, messageId, chatId, openId, chatType, route, started);
    return;
}
```

Create `processWithJavaMcpRunner(...)` using these exact responsibilities:

```java
private void processWithJavaMcpRunner(String requestId, String question, String messageId, String chatId, String openId, String chatType, IntentRoute route, long started) {
    TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
    if (context.hasError()) {
        String answer = projectDataUnavailableMessage();
        feishuClient.replyAnswerCard(messageId, question, answer, "项目数据暂不可用", "orange");
        auditService.writeMain(requestId, openId, chatId, null, List.of(), question, route.skillId(), answer, System.currentTimeMillis() - started, context.errorMessage());
        return;
    }
    List<Map<String, Object>> availableTools = loadAvailableTools(context.tokens());
    List<Map<String, Object>> allResults = new ArrayList<>();
    DeepSeekToolPlan plan = null;
    String answer = null;
    for (int round = 1; round <= properties.agent().maxDecisionRounds(); round++) {
        plan = deepSeekClient.planMcpToolCalls(requestId, question, chatType, availableTools, allResults);
        traceEventService.write(TraceEventService.TraceEvent.succeeded(requestId, round, round, "model.plan", "DeepSeek MCP planning round " + round, Map.of("question", question, "toolResultCount", allResults.size()), plan));
        if (plan.needClarification()) {
            String clarification = hasText(plan.clarificationQuestion()) ? plan.clarificationQuestion() : "请补充项目名称或查询范围。";
            feishuClient.replyAnswerCard(messageId, question, clarification, "需要补充信息", "orange");
            auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(), List.of(), question, route.skillId(), clarification, System.currentTimeMillis() - started, null);
            return;
        }
        if (plan.finalAnswerReady() && hasText(plan.answer())) {
            answer = plan.answer();
            break;
        }
        if (plan.toolCalls() == null || plan.toolCalls().isEmpty()) {
            break;
        }
        allResults.addAll(mcpExecutionRunner.execute(requestId, context.primelayerUserId(), context.tokens(), plan.toolCalls(), availableTools));
    }
    if (!hasText(answer)) {
        answer = deepSeekClient.summarize(question, allResults);
    }
    traceEventService.write(TraceEventService.TraceEvent.succeeded(requestId, 900, 0, "final.answer", "Final Feishu answer", Map.of("question", question), Map.of("answer", answer)));
    feishuClient.replyAnswerCard(messageId, question, answer, route.title(), route.cardTemplate());
    auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(), context.tokens().stream().map(TokenResolver.TokenEntry::projectId).toList(), question, route.skillId(), answer, System.currentTimeMillis() - started, null);
}
```

- [ ] **Step 5: Run orchestrator tests**

Run:

```bash
cd backend
mvn -Dtest=AgentOrchestratorTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

Run:

```bash
git add backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java backend/src/test/java/com/larkconnect/agent/agent/AgentOrchestratorTest.java
git commit -m "feat: route local mcp flow through java runner"
```

---

### Task 5: Add Admin Audit Detail API

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/AdminDtos.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/AdminRepository.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/AdminService.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/AdminController.java`

- [ ] **Step 1: Add DTO records**

Append to `AdminDtos`:

```java
public record AuditTraceEventResponse(
        Long id,
        String requestId,
        Integer stepIndex,
        Integer roundIndex,
        Long parentStepId,
        String eventType,
        String status,
        String summary,
        Object input,
        Object output,
        Object error,
        String projectId,
        String projectName,
        String primelayerUserId,
        String toolName,
        Object pageInfo,
        Long latencyMs,
        Object createdAt
) {}

public record AuditLogDetailResponse(
        Map<String, Object> auditLog,
        Map<String, Object> agentTask,
        List<AuditTraceEventResponse> traceEvents,
        Map<String, Object> graph
) {}
```

- [ ] **Step 2: Add repository queries**

Add to `AdminRepository`:

```java
public Map<String, Object> findAuditLogByRequestId(String requestId) {
    return jdbcTemplate.queryForMap("select * from agent_audit_log where request_id = ?", requestId);
}

public Map<String, Object> findAgentTaskByRequestId(String requestId) {
    return jdbcTemplate.queryForMap("select * from agent_task where request_id = ?", requestId);
}

public List<Map<String, Object>> listTraceEvents(String requestId) {
    return jdbcTemplate.queryForList("""
            select id, request_id, step_index, round_index, parent_step_id, event_type, status,
              summary, input_json, output_json, error_json, project_id, project_name,
              primelayer_user_id, tool_name, page_info, latency_ms, created_at
            from agent_trace_event
            where request_id = ?
            order by step_index asc, id asc
            """, requestId);
}
```

- [ ] **Step 3: Add service method**

Add to `AdminService`:

```java
public AdminDtos.AuditLogDetailResponse auditLogDetail(String requestId) {
    Map<String, Object> auditLog = repository.findAuditLogByRequestId(requestId);
    Map<String, Object> agentTask = repository.findAgentTaskByRequestId(requestId);
    List<AdminDtos.AuditTraceEventResponse> events = repository.listTraceEvents(requestId).stream()
            .map(this::traceEventResponse)
            .toList();
    Map<String, Object> graph = Map.of(
            "nodes", events.stream().map(event -> Map.of(
                    "id", event.id(),
                    "label", event.eventType(),
                    "status", event.status(),
                    "summary", event.summary()
            )).toList()
    );
    return new AdminDtos.AuditLogDetailResponse(auditLog, agentTask, events, graph);
}

private AdminDtos.AuditTraceEventResponse traceEventResponse(Map<String, Object> row) {
    return new AdminDtos.AuditTraceEventResponse(
            ((Number) row.get("id")).longValue(),
            String.valueOf(row.get("request_id")),
            ((Number) row.get("step_index")).intValue(),
            ((Number) row.get("round_index")).intValue(),
            row.get("parent_step_id") == null ? null : ((Number) row.get("parent_step_id")).longValue(),
            String.valueOf(row.get("event_type")),
            String.valueOf(row.get("status")),
            (String) row.get("summary"),
            row.get("input_json"),
            row.get("output_json"),
            row.get("error_json"),
            (String) row.get("project_id"),
            (String) row.get("project_name"),
            (String) row.get("primelayer_user_id"),
            (String) row.get("tool_name"),
            row.get("page_info"),
            row.get("latency_ms") == null ? null : ((Number) row.get("latency_ms")).longValue(),
            row.get("created_at")
    );
}
```

- [ ] **Step 4: Add controller endpoint**

Modify `AdminController` imports and method:

```java
import org.springframework.web.bind.annotation.PathVariable;
```

```java
@GetMapping("/audit-logs/{requestId}")
public ApiResponse<AdminDtos.AuditLogDetailResponse> auditLogDetail(@PathVariable String requestId) {
    return ApiResponse.ok(adminService.auditLogDetail(requestId));
}
```

- [ ] **Step 5: Run backend compile tests**

Run:

```bash
cd backend
mvn -DskipTests compile
```

Expected: SUCCESS.

- [ ] **Step 6: Commit Task 5**

Run:

```bash
git add backend/src/main/java/com/larkconnect/agent/admin/AdminDtos.java backend/src/main/java/com/larkconnect/agent/admin/AdminRepository.java backend/src/main/java/com/larkconnect/agent/admin/AdminService.java backend/src/main/java/com/larkconnect/agent/admin/AdminController.java
git commit -m "feat: expose audit trace detail api"
```

---

### Task 6: Add Admin Trace Detail UI

**Files:**
- Modify: `admin-web/src/api/admin.ts`
- Modify: `admin-web/src/router/index.ts`
- Modify: `admin-web/src/views/AuditLogsView.vue`
- Create: `admin-web/src/views/AuditLogDetailView.vue`

- [ ] **Step 1: Add API method**

Modify `admin-web/src/api/admin.ts`:

```ts
export interface AuditLogDetail {
  auditLog: Record<string, unknown>
  agentTask: Record<string, unknown>
  traceEvents: Record<string, unknown>[]
  graph: Record<string, unknown>
}
```

Add to `adminApi`:

```ts
getAuditLogDetail: (requestId: string) =>
  http.get<unknown, AuditLogDetail>(`/api/admin/audit-logs/${encodeURIComponent(requestId)}`),
```

- [ ] **Step 2: Add route**

Modify `admin-web/src/router/index.ts`:

```ts
import AuditLogDetailView from '../views/AuditLogDetailView.vue'
```

Add child route:

```ts
{ path: 'audit-logs/:requestId', component: AuditLogDetailView },
```

- [ ] **Step 3: Add detail action from audit list**

Replace `admin-web/src/views/AuditLogsView.vue` columns with:

```ts
const columns = [
  { title: '请求 ID', dataIndex: 'request_id', width: 240 },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: 'Primelayer 用户', dataIndex: 'primelayer_user_id' },
  { title: '项目', dataIndex: 'project_ids' },
  { title: '意图', dataIndex: 'intent' },
  { title: '耗时', dataIndex: 'latency_ms' },
  { title: '错误', dataIndex: 'error_message' },
  timeColumn('创建时间', 'created_at'),
  {
    title: '操作',
    key: 'action',
    customRender: ({ record }: { record: Record<string, unknown> }) =>
      h(RouterLink, { to: `/audit-logs/${record.request_id}` }, () => '详情')
  }
]
```

Also import:

```ts
import { h } from 'vue'
import { RouterLink } from 'vue-router'
```

- [ ] **Step 4: Create detail view**

Create `admin-web/src/views/AuditLogDetailView.vue`:

```vue
<template>
  <section class="page audit-detail">
    <div class="page-header">
      <div>
        <h1 class="page-title">调用链路详情</h1>
        <p class="muted">{{ requestId }}</p>
      </div>
      <a-button @click="load">刷新</a-button>
    </div>

    <a-spin :spinning="loading">
      <a-descriptions v-if="detail" bordered size="small" :column="2">
        <a-descriptions-item label="问题">{{ detail.agentTask?.message_text || detail.auditLog?.user_question }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ detail.agentTask?.status || '-' }}</a-descriptions-item>
        <a-descriptions-item label="飞书用户">{{ detail.auditLog?.feishu_open_id || '-' }}</a-descriptions-item>
        <a-descriptions-item label="项目">{{ detail.auditLog?.project_ids || '-' }}</a-descriptions-item>
        <a-descriptions-item label="耗时">{{ detail.auditLog?.latency_ms || '-' }}</a-descriptions-item>
        <a-descriptions-item label="意图">{{ detail.auditLog?.intent || '-' }}</a-descriptions-item>
      </a-descriptions>

      <div v-if="detail" class="trace-layout">
        <a-timeline class="trace-timeline">
          <a-timeline-item
            v-for="event in detail.traceEvents"
            :key="String(event.id)"
            :color="event.status === 'FAILED' ? 'red' : 'green'"
          >
            <button class="trace-node" @click="selected = event">
              <strong>{{ event.eventType }}</strong>
              <span>{{ event.summary || '-' }}</span>
              <small>{{ event.toolName || event.projectName || '' }}</small>
            </button>
          </a-timeline-item>
        </a-timeline>

        <a-card class="trace-panel" size="small" title="节点详情">
          <template v-if="selected">
            <a-descriptions size="small" :column="1">
              <a-descriptions-item label="状态">{{ selected.status }}</a-descriptions-item>
              <a-descriptions-item label="耗时">{{ selected.latencyMs || 0 }}ms</a-descriptions-item>
              <a-descriptions-item label="项目">{{ selected.projectName || selected.projectId || '-' }}</a-descriptions-item>
              <a-descriptions-item label="工具">{{ selected.toolName || '-' }}</a-descriptions-item>
            </a-descriptions>
            <a-collapse>
              <a-collapse-panel key="input" header="输入 JSON">
                <pre>{{ formatJson(selected.input) }}</pre>
              </a-collapse-panel>
              <a-collapse-panel key="output" header="输出 JSON">
                <pre>{{ formatJson(selected.output) }}</pre>
              </a-collapse-panel>
              <a-collapse-panel key="error" header="错误 JSON">
                <pre>{{ formatJson(selected.error) }}</pre>
              </a-collapse-panel>
            </a-collapse>
          </template>
          <a-empty v-else description="请选择一个链路节点" />
        </a-card>
      </div>
    </a-spin>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { adminApi, type AuditLogDetail } from '../api/admin'

const route = useRoute()
const requestId = computed(() => String(route.params.requestId || ''))
const loading = ref(false)
const detail = ref<AuditLogDetail | null>(null)
const selected = ref<Record<string, unknown> | null>(null)

function formatJson(value: unknown) {
  if (value == null) return 'null'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

async function load() {
  loading.value = true
  try {
    detail.value = await adminApi.getAuditLogDetail(requestId.value)
    selected.value = detail.value.traceEvents[0] || null
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载链路详情失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.muted { color: #6b7280; margin: 4px 0 0; }
.trace-layout { display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 16px; margin-top: 16px; align-items: start; }
.trace-timeline { background: #fff; padding: 16px 16px 4px; border: 1px solid #edf0f5; border-radius: 8px; }
.trace-node { display: grid; gap: 4px; width: 100%; text-align: left; border: 0; background: transparent; cursor: pointer; padding: 0; }
.trace-node span { color: #374151; }
.trace-node small { color: #6b7280; }
.trace-panel { position: sticky; top: 16px; }
pre { max-height: 360px; overflow: auto; white-space: pre-wrap; word-break: break-word; background: #f8fafc; padding: 12px; border-radius: 6px; }
@media (max-width: 960px) { .trace-layout { grid-template-columns: 1fr; } .trace-panel { position: static; } }
</style>
```

- [ ] **Step 5: Run frontend checks**

Run:

```bash
cd admin-web
npm run build
```

Expected: build succeeds.

- [ ] **Step 6: Commit Task 6**

Run:

```bash
git add admin-web/src/api/admin.ts admin-web/src/router/index.ts admin-web/src/views/AuditLogsView.vue admin-web/src/views/AuditLogDetailView.vue
git commit -m "feat: add audit trace detail view"
```

---

### Task 7: Reuse Trace Flow In Admin Debug And Verify End To End

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/DebugService.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/DebugDtos.java`
- Modify: `admin-web/src/views/TestCenterView.vue`

- [ ] **Step 1: Make debug agent query return request ID**

Find the existing debug agent query method in `DebugService`. Ensure it creates a request ID:

```java
String requestId = UUID.randomUUID().toString();
```

Return it in the debug response:

```java
response.put("requestId", requestId);
response.put("traceDetailPath", "/audit-logs/" + requestId);
```

When the debug method calls the Java runner, pass this `requestId` so it writes the same `agent_trace_event` rows as the Feishu path.

- [ ] **Step 2: Add debug UI link**

In `admin-web/src/views/TestCenterView.vue`, after rendering debug response JSON, add an action link when `result.requestId` exists:

```vue
<RouterLink v-if="result?.requestId" :to="`/audit-logs/${result.requestId}`">
  查看调用链路
</RouterLink>
```

Import `RouterLink` if the component does not already use it:

```ts
import { RouterLink } from 'vue-router'
```

- [ ] **Step 3: Run backend and frontend checks**

Run:

```bash
cd backend
mvn test
```

Expected: all backend tests pass.

Run:

```bash
cd admin-web
npm run build
```

Expected: frontend build succeeds.

- [ ] **Step 4: Commit Task 7**

Run:

```bash
git add backend/src/main/java/com/larkconnect/agent/admin/DebugService.java backend/src/main/java/com/larkconnect/agent/admin/DebugDtos.java admin-web/src/views/TestCenterView.vue
git commit -m "feat: link debug agent runs to trace detail"
```

---

### Task 8: Final Verification And Documentation Sweep

**Files:**
- Modify only files needed to fix verification failures.

- [ ] **Step 1: Run full backend test suite**

Run:

```bash
cd backend
mvn test
```

Expected: all tests pass.

- [ ] **Step 2: Run frontend production build**

Run:

```bash
cd admin-web
npm run build
```

Expected: build succeeds and TypeScript reports no errors.

- [ ] **Step 3: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intended implementation files are modified. `.superpowers/` remains untracked unless intentionally ignored in a separate housekeeping commit.

- [ ] **Step 4: Commit verification fixes if any**

If Step 1 or Step 2 required fixes, commit them:

```bash
git add backend admin-web
git commit -m "fix: stabilize mcp trace verification"
```

If no fixes were needed, do not create an empty commit.
