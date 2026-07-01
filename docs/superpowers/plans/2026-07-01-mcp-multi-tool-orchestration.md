# MCP 多工具编排 + 分页循环 + 链调可视化 实现计划

**日期**: 2026-07-01
**基于**: `docs/superpowers/specs/2026-07-01-mcp-multi-tool-orchestration-design.md`

---

## Goal

实现 DeepSeek 驱动的多 MCP 工具自动编排：一次提问 → DeepSeek 规划多个工具 → 每个工具 Offset 分页拉取全量数据 → 两阶段 DeepSeek 数据分析 → 管理后台 Mermaid 链调流程图。

## Architecture

```
AgentOrchestrator.processWithDeepSeekPlan()
  ├── DeepSeekClient.planMultiTool()         → List<ToolCall>（新增方法）
  ├── McpAdapter.callToolWithPagination()    → PaginationResult（新增方法）
  ├── DeepSeekClient.analyzePerTool()        → String（新增方法）
  ├── DeepSeekClient.analyzeCross()          → String（新增方法）
  └── ChainTraceService.save()              → agent_chain_trace（新增 Service）
```

## Tech Stack

- Java 17, Spring Boot 3.3.7, Maven
- Vue 3, Ant Design Vue 4, Vite 6, TypeScript
- Mermaid.js (npm: mermaid)
- MySQL 8 + Flyway
- DeepSeek API (chat/completions)

## Global Constraints

- 分页硬上限: 50 页
- 每页默认大小: 100 条
- DeepSeek API key 不可用时降级为启发式规划
- 单页 MCP 失败不中断整体流程
- 单节点 response JSON > 10KB 自动截断存储
- 仅改造 DeepSeek 路径 (`processLegacy`)，不影响 AgentService 路径
- Mermaid 节点 > 50 时自动折叠分页节点为子图

---

## File Structure

| 文件 | 职责 |
|------|------|
| `backend/.../deepseek/DeepSeekClient.java` | 新增 `planMultiTool()`, `analyzePerTool()`, `analyzeCross()` |
| `backend/.../mcp/McpAdapter.java` | 新增 `callToolWithPagination()`, `PaginationResult` record, `PageData` record |
| `backend/.../agent/AgentOrchestrator.java` | 重构 `processLegacy()` → `processWithDeepSeekPlan()` |
| `backend/.../audit/ChainTraceService.java` | **新文件**: 链调数据构建、保存、查询 |
| `backend/.../audit/AuditService.java` | writeTool() 新增 page/pageSize/totalCount |
| `backend/.../admin/AdminController.java` | 新增 GET `/api/admin/chain-trace/{requestId}` |
| `backend/.../db/migration/V6__chain_trace.sql` | **新文件**: agent_chain_trace 表 + agent_tool_call_log 扩展 |
| `admin-web/src/views/ChainTraceView.vue` | **新文件**: Mermaid 流程图 + 数据明细 |
| `admin-web/src/views/AgentTasksView.vue` | 操作列新增「链调详情」按钮 |
| `admin-web/src/api/admin.ts` | 新增 `getChainTrace()` |
| `admin-web/src/router/index.ts` | 新增 `/chain-trace/:requestId` 路由 |
| `admin-web/package.json` | 新增 `mermaid` 依赖 |

---

## Tasks

### Task 1: 数据库迁移 V6

**文件**: `backend/src/main/resources/db/migration/V6__chain_trace.sql`

**内容**:
```sql
CREATE TABLE agent_chain_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL,
  trace_data JSON NOT NULL COMMENT '完整链调 JSON',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chain_trace_request (request_id)
);

ALTER TABLE agent_tool_call_log
  ADD COLUMN page INT DEFAULT 0,
  ADD COLUMN page_size INT DEFAULT 100,
  ADD COLUMN total_count INT DEFAULT 0;
```

**验证**: 启动应用，Flyway 自动执行迁移，`show tables` 包含 `agent_chain_trace`

---

### Task 2: McpAdapter 分页方法

**文件**: `backend/src/main/java/com/larkconnect/agent/mcp/McpAdapter.java`

**新增 record**:
```java
record PaginationResult(
    List<PageData> pages,
    int totalCount,
    int totalPages,
    int successPages,
    int failedPages
) {}

record PageData(
    int page,
    int pageSize,
    String status,          // SUCCEEDED / FAILED
    Map<String, Object> rawRequest,
    Map<String, Object> rawResponse,
    String error,
    long latencyMs
) {}
```

**新增方法**:
```java
private static final int DEFAULT_PAGE_SIZE = 100;
private static final int MAX_PAGES = 50;

public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments) {
    return callToolWithPagination(token, toolName, arguments, DEFAULT_PAGE_SIZE);
}

public PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> arguments, int pageSize) {
    List<PageData> pages = new ArrayList<>();
    int offset = 0;
    int totalCount = 0;
    int successPages = 0;
    int failedPages = 0;

    // First page
    Map<String, Object> pageArgs = new LinkedHashMap<>(arguments);
    pageArgs.put("offset", offset);
    pageArgs.put("limit", pageSize);
    
    long started = System.currentTimeMillis();
    Map<String, Object> rawRequest = buildRequestPayload(toolName, pageArgs);
    try {
        Map<String, Object> response = callTool(token, toolName, pageArgs);
        long latency = System.currentTimeMillis() - started;
        totalCount = extractTotalCount(response);
        pages.add(new PageData(0, pageSize, Status.SUCCEEDED, rawRequest, response, null, latency));
        successPages++;
    } catch (Exception e) {
        long latency = System.currentTimeMillis() - started;
        pages.add(new PageData(0, pageSize, Status.FAILED, rawRequest, null, readableError(e), latency));
        failedPages++;
        return new PaginationResult(pages, 0, 1, successPages, failedPages);
    }

    if (totalCount <= pageSize) {
        return new PaginationResult(pages, totalCount, 1, successPages, failedPages);
    }

    int totalPages = Math.min((int) Math.ceil((double) totalCount / pageSize), MAX_PAGES);

    for (int page = 1; page < totalPages; page++) {
        offset = page * pageSize;
        Map<String, Object> args = new LinkedHashMap<>(arguments);
        args.put("offset", offset);
        args.put("limit", pageSize);

        started = System.currentTimeMillis();
        Map<String, Object> req = buildRequestPayload(toolName, args);
        try {
            Map<String, Object> resp = callTool(token, toolName, args);
            long latency = System.currentTimeMillis() - started;
            pages.add(new PageData(page, pageSize, Status.SUCCEEDED, req, resp, null, latency));
            successPages++;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - started;
            pages.add(new PageData(page, pageSize, Status.FAILED, req, null, readableError(e), latency));
            failedPages++;
        }
    }

    return new PaginationResult(pages, totalCount, totalPages, successPages, failedPages);
}

private int extractTotalCount(Map<String, Object> response) {
    Object result = response.get("result");
    Map<String, Object> resultMap = result instanceof Map ? castMap(result) : response;
    if (resultMap.containsKey("totalCount")) {
        return toInt(resultMap.get("totalCount"));
    }
    if (resultMap.containsKey("total")) {
        return toInt(resultMap.get("total"));
    }
    if (resultMap.containsKey("count")) {
        return toInt(resultMap.get("count"));
    }
    Object items = resultMap.get("items");
    if (items instanceof List<?> list) {
        return list.size();
    }
    Object data = resultMap.get("data");
    if (data instanceof List<?> list) {
        return list.size();
    }
    return 0;
}

private int toInt(Object value) {
    if (value instanceof Number n) return n.intValue();
    if (value instanceof String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
    }
    return 0;
}

private Map<String, Object> buildRequestPayload(String toolName, Map<String, Object> arguments) {
    return Map.of(
        "jsonrpc", "2.0",
        "id", UUID.randomUUID().toString(),
        "method", "tools/call",
        "params", Map.of("name", toolName, "arguments", arguments)
    );
}

@SuppressWarnings("unchecked")
private Map<String, Object> castMap(Object obj) {
    return (Map<String, Object>) obj;
}

private String readableError(Exception e) {
    String msg = e.getMessage();
    if (msg == null || msg.isBlank()) return "MCP 调用失败";
    return msg.length() > 500 ? msg.substring(0, 500) : msg;
}
```

---

### Task 3: DeepSeekClient 新增多工具规划 + 两阶段分析

**文件**: `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekClient.java`

**3.1 planMultiTool**

```java
public DeepSeekPlan planMultiTool(String requestId, String question, String chatType, List<Map<String, Object>> availableTools) {
    if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
        return heuristicPlan(question, chatType);
    }
    try {
        String toolsJson = objectMapper.writeValueAsString(availableTools);
        String prompt = """
            你是 Agent Gateway 的意图规划器。只能输出 JSON，不要输出解释。
            JSON 字段：
            {
              "intent": "string",
              "projectScope": "single_project|current_chat_project|all_accessible_projects|unknown",
              "projectHints": ["项目名"],
              "toolCalls": [
                {
                  "toolName": "必须是可用工具列表中的名称",
                  "arguments": {"参数名": "值"},
                  "reason": "为什么选择这个工具"
                }
              ],
              "needClarification": false,
              "clarificationQuestion": null,
              "answerStyle": "normal"
            }
            
            重要规则：
            - toolCalls 是数组，可以包含多个工具（0-5个），一次性规划所有需要调用的工具
            - 每个 toolName 必须严格来自可用工具列表
            - 不要选择功能重复的工具，优先选择名称中包含 query_/get_/list_/search_ 的只读工具
            - 如果用户问题涉及多个维度（如同时问任务和健康度），选择一个覆盖最广的组合
            - arguments 不传 token、密钥或认证信息
            
            可用工具：%s
            用户问题：%s
            """.formatted(toolsJson, question);

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
        return normalizePlan(content.asText(), question, chatType);
    } catch (Exception e) {
        return heuristicPlan(question, chatType);
    }
}
```

**3.2 analyzePerTool**

```java
public String analyzePerTool(String toolName, List<Map<String, Object>> allPageResults) {
    if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
        return "工具 " + toolName + " 返回 " + allPageResults.size() + " 页数据（未分析）";
    }
    try {
        String dataJson = objectMapper.writeValueAsString(allPageResults);
        String prompt = """
            你是数据分析助手。基于 MCP 工具返回的全部数据做独立分析。
            
            工具名称：%s
            数据（已合并所有分页）：%s
            
            请输出结构化分析：
            1. 数据总量和关键统计
            2. 数据中的主要发现和趋势
            3. 异常值和需要关注的风险点
            4. 数据质量评估（是否完整、是否有缺失）
            
            只分析数据本身，不要提出需要补充查询的建议。
            """.formatted(toolName, dataJson.length() > 8000 ? dataJson.substring(0, 8000) + "...(truncated)" : dataJson);

        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(Map.of(
                        "model", properties.deepseek().model(),
                        "messages", List.of(Map.of("role", "user", "content", prompt)),
                        "temperature", 0.2
                ))
                .retrieve()
                .body(Map.class);

        return objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content").asText();
    } catch (Exception e) {
        return "工具 " + toolName + " 数据分析失败: " + sanitizeError(e.getMessage());
    }
}
```

**3.3 analyzeCross**

```java
public String analyzeCross(String question, List<PerToolAnalysis> perToolAnalyses) {
    if (properties.deepseek().apiKey() == null || properties.deepseek().apiKey().isBlank()) {
        return fallbackSummary(question, List.of());
    }
    try {
        StringBuilder analyses = new StringBuilder();
        for (int i = 0; i < perToolAnalyses.size(); i++) {
            PerToolAnalysis a = perToolAnalyses.get(i);
            analyses.append("[").append(i + 1).append("] 工具: ").append(a.toolName()).append("\n")
                    .append("分析: ").append(a.analysis()).append("\n\n");
        }

        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .body(Map.of(
                        "model", properties.deepseek().model(),
                        "messages", List.of(Map.of("role", "user", "content", """
                            你是商业数据分析师。基于多个维度独立分析结果，做交叉综合分析，输出最终答案。
                            
                            用户原始问题：%s
                            
                            各维度独立分析：
                            %s
                            
                            要求：
                            - 用自然语言回答用户问题，直接给结论
                            - 引用各维度关键数据支撑结论
                            - 说明数据范围和局限性
                            - 不要编造数据
                            """.formatted(question, analyses.toString()))),
                        "temperature", 0.2
                ))
                .retrieve()
                .body(Map.class);

        return objectMapper.valueToTree(response).path("choices").path(0).path("message").path("content").asText();
    } catch (Exception e) {
        return fallbackSummary(question, List.of());
    }
}

// New record for PerToolAnalysis
public record PerToolAnalysis(String toolName, String analysis) {}
```

---

### Task 4: ChainTraceService 链调数据服务

**文件**: `backend/src/main/java/com/larkconnect/agent/audit/ChainTraceService.java`（新文件）

```java
package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.mcp.McpAdapter.PageData;
import com.larkconnect.agent.mcp.McpAdapter.PaginationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChainTraceService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChainTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(String requestId, ChainTrace trace) {
        jdbcTemplate.update(
            "insert into agent_chain_trace(request_id, trace_data) values (?, cast(? as json)) on duplicate key update trace_data = values(trace_data)",
            requestId, toJson(trace.toMap())
        );
    }

    public Optional<Map<String, Object>> load(String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select trace_data from agent_chain_trace where request_id = ?", requestId
        );
        if (rows.isEmpty()) return Optional.empty();
        Object data = rows.get(0).get("trace_data");
        if (data instanceof Map<?, ?> m) {
            return Optional.of(castMap(m));
        }
        try {
            return Optional.of(objectMapper.readValue(String.valueOf(data), Map.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object m) {
        return (Map<String, Object>) m;
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }
}
```

**文件**: `backend/src/main/java/com/larkconnect/agent/audit/ChainTrace.java`（新文件）

```java
package com.larkconnect.agent.audit;

import java.util.*;

public class ChainTrace {
    private String requestId;
    private List<Node> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();
    private Summary summary = new Summary();

    public ChainTrace(String requestId) { this.requestId = requestId; }

    public record Node(
        String id, String type, String label,
        String status, long latencyMs,
        Map<String, Object> metadata
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("label", label);
            map.put("status", status);
            map.put("latencyMs", latencyMs);
            if (metadata != null) map.putAll(metadata);
            return map;
        }
    }

    public record Edge(String from, String to) {
        Map<String, String> toMap() { return Map.of("from", from, "to", to); }
    }

    public static class Summary {
        int totalMcpCalls, totalPages;
        long totalLatencyMs;
        List<String> toolsUsed = new ArrayList<>();
        List<String> projectsQueried = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalMcpCalls", totalMcpCalls);
            map.put("totalPages", totalPages);
            map.put("totalLatencyMs", totalLatencyMs);
            map.put("toolsUsed", toolsUsed);
            map.put("projectsQueried", projectsQueried);
            return map;
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", requestId);
        map.put("nodes", nodes.stream().map(Node::toMap).toList());
        map.put("edges", edges.stream().map(Edge::toMap).toList());
        map.put("summary", summary.toMap());
        return map;
    }

    // --- Builder methods ---
    public void addNode(Node node) { nodes.add(node); }
    public void addEdge(Edge edge) { edges.add(edge); }

    public void addPlanNode(String input, String output, long latencyMs) {
        int idx = nodes.size() + 1;
        addNode(new Node("plan", "model_call", "DeepSeek 工具规划", "SUCCEEDED", latencyMs,
            Map.of("modelName", "deepseek-chat", "input", truncate(input, 2000), "output", truncate(output, 2000))));
    }

    public void addMcpCallNode(String toolName, String projectId, String projectName, PageData page, int pageIdx) {
        String nodeId = "tool_" + sanitizeId(toolName) + "_" + sanitizeId(projectId) + "_p" + pageIdx;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("toolName", toolName);
        meta.put("projectId", projectId);
        meta.put("projectName", projectName);
        meta.put("page", page.page());
        meta.put("pageSize", page.pageSize());
        meta.put("request", toMapString(page.rawRequest()));
        meta.put("response", toMapString(page.rawResponse()));
        addNode(new Node(nodeId, "mcp_call",
            toolName + " (" + (projectName != null ? projectName : projectId) + ", 第" + (page.page() + 1) + "页)",
            page.status(), page.latencyMs(), meta));
    }

    public void addAnalyzeNode(String nodeId, String label, String input, String output, long latencyMs) {
        addNode(new Node(nodeId, "model_call", label, "SUCCEEDED", latencyMs,
            Map.of("modelName", "deepseek-chat", "input", truncate(input, 2000), "output", truncate(output, 3000))));
    }

    public String lastNodeId() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).id();
    }

    // Helpers
    private String sanitizeId(String s) { return (s != null ? s : "unknown").replaceAll("[^a-zA-Z0-9_]", "_"); }
    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) + "...(truncated)" : s; }
    private String toMapString(Object obj) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj); }
        catch (Exception e) { return String.valueOf(obj); }
    }
}
```

---

### Task 5: AgentOrchestrator 主流程重构

**文件**: `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java`

**修改**: `process()` 方法中第 103 行 `processLegacy(...)` 替换为 `processWithDeepSeekPlan(...)`

**新增方法 `processWithDeepSeekPlan`**:

```java
private void processWithDeepSeekPlan(String requestId, String question, String messageId, 
        String chatId, String openId, String chatType, IntentRoute route, long started) {
    
    // 1. Resolve context
    TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(openId, chatId, chatType, 
            properties.agent().maxProjectsPerQuery());
    if (context.hasError()) {
        String error = context.errorMessage();
        String answer = configurationHint(error);
        feishuClient.replyAnswerCard(messageId, question, answer, "MCP 配置异常", "orange");
        auditService.writeMain(requestId, openId, chatId, null, List.of(), question, 
                "deepseek_multi_tool", answer, System.currentTimeMillis() - started, error);
        return;
    }

    // 2. Load available tools
    List<Map<String, Object>> availableTools = loadAvailableTools(context.tokens());
    if (availableTools.isEmpty()) {
        feishuClient.replyAnswerCard(messageId, question, "当前没有可用的 MCP 工具。", "工具不可用", "orange");
        auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(), List.of(), question,
                "deepseek_multi_tool", "无可用 MCP 工具", System.currentTimeMillis() - started, null);
        return;
    }

    // 3. Build chain trace
    ChainTrace trace = new ChainTrace(requestId);

    // 4. DeepSeek plan (multi tool)
    DeepSeekPlan plan = deepSeekClient.planMultiTool(requestId, question, chatType, availableTools);
    trace.addPlanNode(question + "\n\n可用工具: " + availableTools.size() + " 个", 
            toJson(plan), System.currentTimeMillis() - started);
    auditService.writeModel(requestId, properties.deepseek().model(), "plan_multi_tool", question, 
            toJson(plan), Status.SUCCEEDED, 0, null);

    if (plan.needClarification()) {
        feishuClient.replyAnswerCard(messageId, question, plan.clarificationQuestion(), "需要补充信息", "orange");
        auditService.writeMain(requestId, openId, chatId, null, List.of(), question, 
                plan.intent(), plan.clarificationQuestion(), System.currentTimeMillis() - started, null);
        return;
    }

    // 5. Execute all tool calls with pagination
    Map<String, List<Map<String, Object>>> allToolResults = new LinkedHashMap<>(); // toolName -> all results
    int totalPages = 0;
    int totalMcpCalls = 0;
    List<String> toolNames = new ArrayList<>();
    List<String> projectNames = new ArrayList<>();
    String prevNodeId = "plan";

    for (DeepSeekPlan.ToolCall toolCall : plan.toolCalls()) {
        String toolName = toolCall.toolName();
        toolNames.add(toolName);
        List<Map<String, Object>> toolAllResults = new ArrayList<>();

        for (TokenResolver.TokenEntry token : context.tokens()) {
            if (!projectNames.contains(token.projectId())) {
                projectNames.add(token.projectId());
            }
            Map<String, Object> args = new LinkedHashMap<>(toolCall.arguments());
            args.put("project_id", token.projectId());
            args.put("primelayer_user_id", context.primelayerUserId());

            long toolStarted = System.currentTimeMillis();
            PaginationResult result = mcpAdapter.callToolWithPagination(token.token(), toolName, args);
            totalPages += result.totalPages();
            totalMcpCalls += result.pages().size();

            // Record each page in chain trace
            for (int i = 0; i < result.pages().size(); i++) {
                PageData page = result.pages().get(i);
                trace.addMcpCallNode(toolName, token.projectId(), token.projectName(), page, i);
                String nodeId = trace.lastNodeId();
                trace.addEdge(new ChainTrace.Edge(prevNodeId, nodeId));
                prevNodeId = nodeId;

                // Record audit
                auditService.writeTool(requestId, token.projectId(), context.primelayerUserId(), 
                        toolName, args, page.status(), page.latencyMs(), page.error());
            }

            // Collect all results for this tool
            for (PageData page : result.pages()) {
                if (Status.SUCCEEDED.equals(page.status()) && page.rawResponse() != null) {
                    toolAllResults.add(page.rawResponse());
                }
            }
        }
        allToolResults.put(toolName, toolAllResults);
    }

    // 6. Stage 1: Per-tool analysis
    List<DeepSeekClient.PerToolAnalysis> perToolAnalyses = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : allToolResults.entrySet()) {
        String toolName = entry.getKey();
        List<Map<String, Object>> results = entry.getValue();
        if (results.isEmpty()) continue;

        long analyzeStarted = System.currentTimeMillis();
        String analysis = deepSeekClient.analyzePerTool(toolName, results);
        long analyzeLatency = System.currentTimeMillis() - analyzeStarted;

        String nodeId = "analyze_per_" + toolName.replaceAll("[^a-zA-Z0-9_]", "_");
        trace.addAnalyzeNode(nodeId, "阶段1: 分析 " + toolName, 
                "全量数据（" + results.size() + " 页）", analysis, analyzeLatency);
        trace.addEdge(new ChainTrace.Edge(prevNodeId, nodeId));
        prevNodeId = nodeId;

        perToolAnalyses.add(new DeepSeekClient.PerToolAnalysis(toolName, analysis));
        auditService.writeModel(requestId, properties.deepseek().model(), "analyze_per_tool_" + toolName,
                "tool=" + toolName + " pages=" + results.size(), analysis, Status.SUCCEEDED, analyzeLatency, null);
    }

    // 7. Stage 2: Cross analysis
    long crossStarted = System.currentTimeMillis();
    String finalAnswer = deepSeekClient.analyzeCross(question, perToolAnalyses);
    long crossLatency = System.currentTimeMillis() - crossStarted;

    trace.addAnalyzeNode("analyze_cross", "阶段2: 交叉分析",
            question + "\n\n" + perToolAnalyses.size() + " 个工具分析结果", finalAnswer, crossLatency);
    trace.addEdge(new ChainTrace.Edge(prevNodeId, "analyze_cross"));
    auditService.writeModel(requestId, properties.deepseek().model(), "analyze_cross",
            question, finalAnswer, Status.SUCCEEDED, crossLatency, null);

    // 8. Update trace summary
    trace.summary.totalMcpCalls = totalMcpCalls;
    trace.summary.totalPages = totalPages;
    trace.summary.totalLatencyMs = System.currentTimeMillis() - started;
    trace.summary.toolsUsed = toolNames;
    trace.summary.projectsQueried = projectNames;

    // 9. Save trace
    chainTraceService.save(requestId, trace);

    // 10. Reply
    String title = "Primelayer AI 回答 (" + getRouteTitle(route) + ")";
    feishuClient.replyAnswerCard(messageId, question, finalAnswer, title, route.cardTemplate());
    auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(),
            projectNames, question, plan.intent(), finalAnswer, System.currentTimeMillis() - started, null);
}
```

**新增 import + 字段**:

```java
import com.larkconnect.agent.audit.ChainTrace;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.mcp.McpAdapter.PageData;
import com.larkconnect.agent.mcp.McpAdapter.PaginationResult;

// 新增字段
private final ChainTraceService chainTraceService;

// 构造函数新增参数
public AgentOrchestrator(..., ChainTraceService chainTraceService) {
    ...
    this.chainTraceService = chainTraceService;
}
```

---

### Task 6: AuditService 扩展 & AdminController 新增接口

**文件**: `backend/src/main/java/com/larkconnect/agent/audit/AuditService.java`

**修改 `writeTool` 方法签名**:
```java
public void writeTool(String requestId, String projectId, String primelayerUserId, 
        String toolName, Map<String, Object> arguments, String status, long latencyMs, String error) {
    jdbcTemplate.update("""
        insert into agent_tool_call_log(request_id, project_id, primelayer_user_id, 
            tool_name, tool_arguments, tool_status, latency_ms, error_message, page, page_size, total_count)
        values (?, ?, ?, ?, cast(? as json), ?, ?, ?, ?, ?, ?)
        """, requestId, projectId, primelayerUserId, toolName, toJson(arguments), 
        status, latencyMs, error, 0, 100, 0);
}
```

注意：分页审计上下文在 `AgentOrchestrator` 中逐页调用此方法时传入 page/pageSize/totalCount 暂不区分（通过 ChainTrace 记录），此处保持兼容性。

**文件**: `backend/src/main/java/com/larkconnect/agent/admin/AdminController.java`

**新增接口**:
```java
private final ChainTraceService chainTraceService;

public AdminController(AdminService adminService, AiRuntimeConfigService aiRuntimeConfigService, 
        ChainTraceService chainTraceService) {
    this.adminService = adminService;
    this.aiRuntimeConfigService = aiRuntimeConfigService;
    this.chainTraceService = chainTraceService;
}

@GetMapping("/chain-trace/{requestId}")
public ApiResponse<Map<String, Object>> getChainTrace(@PathVariable String requestId) {
    return chainTraceService.load(requestId)
            .map(ApiResponse::ok)
            .orElse(ApiResponse.error("链调记录不存在"));
}
```

---

### Task 7: Admin-web 路由 + API

**文件**: `admin-web/src/router/index.ts`

**新增**:
```typescript
import ChainTraceView from '../views/ChainTraceView.vue'

// children 数组中新增:
{ path: 'chain-trace/:requestId', component: ChainTraceView }
```

**文件**: `admin-web/src/api/admin.ts`

**新增**:
```typescript
getChainTrace: (requestId: string) =>
  http.get<unknown, Record<string, unknown>>(`/api/admin/chain-trace/${requestId}`),
```

---

### Task 8: AgentTasksView 新增「链调详情」按钮

**文件**: `admin-web/src/views/AgentTasksView.vue`

**修改**:
```vue
<template>
  <ReadonlyTable title="任务状态" :columns="columns" :load="adminApi.listAgentTasks">
    <template #actions="{ record }">
      <a-button type="link" size="small" @click="viewTrace(record.request_id)">链调详情</a-button>
      <a-tag v-if="record.status" :color="statusColor(record.status)">{{ record.status }}</a-tag>
    </template>
  </ReadonlyTable>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import ReadonlyTable from '../components/ReadonlyTable.vue'
import { adminApi } from '../api/admin'
import { timeColumn } from '../utils/time'

const router = useRouter()

const columns = [
  { title: '请求 ID', dataIndex: 'request_id', width: 240 },
  { title: '消息 ID', dataIndex: 'feishu_message_id' },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: '会话', dataIndex: 'feishu_chat_id' },
  { title: '状态', dataIndex: 'status' },
  { title: '操作', key: 'actions', width: 150 },
  timeColumn('创建时间', 'created_at'),
  timeColumn('完成时间', 'finished_at')
]

function viewTrace(requestId: string) {
  router.push(`/chain-trace/${requestId}`)
}

function statusColor(status: string) {
  switch (status) {
    case 'SUCCEEDED': return 'green'
    case 'FAILED': return 'red'
    case 'RUNNING': return 'blue'
    default: return 'default'
  }
}
</script>
```

---

### Task 9: ChainTraceView.vue 新页面

**文件**: `admin-web/src/views/ChainTraceView.vue`（新文件）

```vue
<template>
  <div style="padding: 24px">
    <a-page-header title="MCP 调用链路" @back="() => $router.back()">
      <template #subTitle>
        请求ID: {{ requestId }}
        <a-tag v-if="traceData" :color="traceData.summary?.totalPages > 0 ? 'green' : 'red'">
          {{ traceData.summary?.totalMcpCalls }} 次 MCP 调用 / {{ traceData.summary?.totalPages }} 页
        </a-tag>
      </template>
    </a-page-header>

    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" :message="error" style="margin-bottom: 16px" />
      
      <template v-if="traceData">
        <!-- Summary cards -->
        <a-row :gutter="16" style="margin-bottom: 16px">
          <a-col :span="6">
            <a-card size="small" title="MCP 调用次数">{{ traceData.summary?.totalMcpCalls ?? 0 }}</a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" title="总页数">{{ traceData.summary?.totalPages ?? 0 }}</a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" title="总耗时">{{ formatMs(traceData.summary?.totalLatencyMs) }}</a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" title="使用工具">{{ (traceData.summary?.toolsUsed || []).join(', ') || '-' }}</a-card>
          </a-col>
        </a-row>

        <!-- Mermaid flow chart -->
        <a-card title="调用流程图" style="margin-bottom: 16px">
          <div ref="mermaidContainer" style="overflow-x: auto; min-height: 200px"></div>
        </a-card>

        <!-- Node detail modal -->
        <a-modal v-model:open="modalOpen" :title="modalTitle" width="900px" :footer="null">
          <a-tabs>
            <a-tab-pane key="input" tab="输入">
              <pre class="json-block">{{ modalInput }}</pre>
            </a-tab-pane>
            <a-tab-pane key="output" tab="输出">
              <pre class="json-block">{{ modalOutput }}</pre>
            </a-tab-pane>
            <a-tab-pane key="meta" tab="元信息">
              <a-descriptions size="small" bordered :column="2">
                <a-descriptions-item v-for="(v, k) in modalMeta" :key="k" :label="String(k)">{{ v }}</a-descriptions-item>
              </a-descriptions>
            </a-tab-pane>
          </a-tabs>
        </a-modal>

        <!-- Detail table -->
        <a-card title="节点明细">
          <a-table :columns="nodeColumns" :dataSource="traceData.nodes || []" 
            rowKey="id" size="small" :pagination="{ pageSize: 20 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'type'">
                <a-tag :color="nodeTypeColor(record.type)">{{ nodeTypeLabel(record.type) }}</a-tag>
              </template>
              <template v-if="column.key === 'status'">
                <a-tag :color="record.status === 'SUCCEEDED' ? 'green' : 'red'">{{ record.status }}</a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a-button type="link" size="small" @click="openNodeDetail(record)">详情</a-button>
              </template>
            </template>
          </a-table>
        </a-card>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi } from '../api/admin'

interface TraceNode {
  id: string
  type: 'model_call' | 'mcp_call'
  label: string
  status: string
  latencyMs: number
  modelName?: string
  toolName?: string
  projectId?: string
  projectName?: string
  page?: number
  request?: string
  response?: string
  input?: string
  output?: string
}

interface TraceData {
  requestId: string
  nodes: TraceNode[]
  edges: { from: string; to: string }[]
  summary: {
    totalMcpCalls: number
    totalPages: number
    totalLatencyMs: number
    toolsUsed: string[]
    projectsQueried: string[]
  }
}

const route = useRoute()
const requestId = String(route.params.requestId)
const loading = ref(true)
const error = ref('')
const traceData = ref<TraceData | null>(null)
const mermaidContainer = ref<HTMLDivElement>()

// Modal state
const modalOpen = ref(false)
const modalTitle = ref('')
const modalInput = ref('')
const modalOutput = ref('')
const modalMeta = ref<Record<string, unknown>>({})

const nodeColumns = [
  { title: '节点', dataIndex: 'label', key: 'label', ellipsis: true },
  { title: '类型', key: 'type', width: 90 },
  { title: '状态', key: 'status', width: 90 },
  { title: '耗时', dataIndex: 'latencyMs', key: 'latency', width: 80, customRender: ({ text }: { text: number }) => formatMs(text) },
  { title: '操作', key: 'action', width: 80 }
]

onMounted(async () => {
  try {
    const data = await adminApi.getChainTrace(requestId) as unknown as TraceData
    traceData.value = data
    await nextTick()
    renderMermaid()
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
})

async function renderMermaid() {
  if (!traceData.value || !mermaidContainer.value) return
  try {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

    const { nodes, edges } = traceData.value
    let chart = 'graph TD\n'
    
    // Build mermaid nodes with click handlers
    for (const node of nodes) {
      const shape = node.type === 'model_call' ? '[' : '['
      const suffix = node.type === 'model_call' ? ']' : ']'
      const color = node.status === 'SUCCEEDED' 
        ? (node.type === 'model_call' ? 'style ' + node.id + ' fill:#e6f7ff,stroke:#1890ff' : 'style ' + node.id + ' fill:#f6ffed,stroke:#52c41a')
        : 'style ' + node.id + ' fill:#fff2f0,stroke:#ff4d4f'
      const label = (node.label || node.id).replace(/[()]/g, '')
      chart += `  ${node.id}${shape}"${label}"${suffix}\n`
      chart += `  ${color}\n`
      chart += `  click ${node.id} call nodeClick("${node.id}")\n`
    }
    
    for (const edge of edges) {
      chart += `  ${edge.from} --> ${edge.to}\n`
    }

    const { svg } = await mermaid.render('mermaid-chart', chart)
    mermaidContainer.value.innerHTML = svg
  } catch (e) {
    console.error('Mermaid render failed:', e)
    if (mermaidContainer.value) {
      mermaidContainer.value.innerHTML = '<p style="color:red">流程图渲染失败: ' + (e as Error).message + '</p>'
    }
  }
}

function openNodeDetail(node: TraceNode) {
  modalTitle.value = node.label || node.id
  modalInput.value = node.input || node.request || ''
  modalOutput.value = node.output || node.response || ''
  modalMeta.value = { ...node }
  delete (modalMeta.value as any).input
  delete (modalMeta.value as any).output
  delete (modalMeta.value as any).request
  delete (modalMeta.value as any).response
  modalOpen.value = true
}

function nodeTypeColor(type: string) { return type === 'model_call' ? 'blue' : 'green' }
function nodeTypeLabel(type: string) { return type === 'model_call' ? '模型调用' : 'MCP 调用' }
function formatMs(ms: number | undefined) {
  if (!ms) return '-'
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms'
}
</script>

<style scoped>
.json-block {
  background: #f5f5f5;
  padding: 16px;
  border-radius: 4px;
  max-height: 500px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.5;
}
:deep(.mermaid svg) { max-width: 100%; }
</style>
```

---

### Task 10: 安装 Mermaid 依赖

**文件**: `admin-web/package.json`

**命令**:
```bash
cd admin-web && npm install mermaid
```

**验证**: `node_modules/mermaid` 存在

---

### Task 11: AgentOrchestrator 单元测试

**文件**: `backend/src/test/java/com/larkconnect/agent/agent/AgentOrchestratorTest.java`

**新增测试**:
```java
@Test
void shouldPlanMultiToolWhenDeepSeekReturnsMultipleCalls() {
    // verify planMultiTool is called
    // verify chain trace is saved with plan node
    // verify edges connect plan → tool calls
}

@Test
void shouldPaginateWhenTotalCountExceedsPageSize() {
    // verify callToolWithPagination is called
    // verify multiple pages are recorded in chain trace
    // verify page nodes are connected sequentially
}

@Test
void shouldAnalyzeInTwoStages() {
    // verify analyzePerTool is called for each tool
    // verify analyzeCross is called with per-tool results
    // verify edges connect per-tool analysis → cross analysis
}
```

---

### Task 12: DeepSeekClient 单元测试

**文件**: `backend/src/test/java/com/larkconnect/agent/deepseek/DeepSeekClientTest.java`

**新增测试**:
```java
@Test
void planMultiToolShouldReturnMultipleCalls() {
    // mock DeepSeek response with toolCalls array
    // verify normalized plan contains correct number of tool calls
}

@Test
void planMultiToolShouldFallbackOnApiError() {
    // verify heuristic fallback when API key is missing
    // verify fallback plan contains at least one tool call
}
```

---

### Task 13: 编译验证 & E2E 测试

**命令**:
```bash
cd backend && mvn compile
cd admin-web && npx vue-tsc --noEmit
```

**验证**:
- Java 编译无错误
- TypeScript 类型检查通过
- 启动应用: `cd backend && mvn spring-boot:run`
- `curl http://localhost:8080/api/admin/chain-trace/test-id` 返回 404（正常，无数据）
- 发送飞书测试消息，验证链调日志写入 `agent_chain_trace` 表
- Admin-web 中 AgentTasks 出现「链调详情」按钮，点击跳转流程图页面
