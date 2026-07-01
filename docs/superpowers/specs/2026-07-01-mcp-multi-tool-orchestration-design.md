# MCP 多工具编排 + 分页循环 + 链调可视化 设计文档

**日期**: 2026-07-01
**状态**: 已确认

---

## 1. 需求概述

当前 Agent Gateway 每次只调用单个 MCP 工具，没有分页能力，调用链路不可视化。需要实现：

1. **多工具规划**: DeepSeek 一次性判断需要调用哪几个 MCP 工具
2. **分页循环**: MCP 返回列表数据时，自动 Offset 分页拉取全部数据
3. **两阶段分析**: 收集全量数据后，DeepSeek 先独立分析每个工具的结果，再交叉分析输出最终答案
4. **链调可视化**: 管理后台用 Mermaid 流程图展示完整 MCP 调用链路，支持节点点击查看原始数据

---

## 2. 总体流程

```
飞书提问 → IntentRouter 路由
  ↓
DeepSeek planMultiTool() → 生成 N 个 ToolCall
  ↓
对每个 ToolCall × 每个 Project → Offset 分页循环拉取
  ├─ page=0 → 得到 totalCount → 计算总页数 → 循环拉取其余页
  └─ 记录每次请求/响应到链调日志
  ↓
两阶段 DeepSeek 数据分析
  ├─ 阶段1: analyzePerTool() 每个工具结果独立分析
  └─ 阶段2: analyzeCross() 合并独立分析 → 最终答案
  ↓
飞书回复 + 链调日志持久化 (agent_chain_trace)
  ↓
Admin-web: AgentTasks → 「链调详情」→ Mermaid 流程图 + 数据明细
```

---

## 3. 模块改动

### 3.1 Java Backend

#### 3.1.1 DeepSeekClient 新增方法

| 方法 | 说明 |
|------|------|
| `DeepSeekPlan planMultiTool(String question, List<Map<String, Object>> tools)` | 调用 DeepSeek，system prompt 要求输出多个 toolCall（JSON 数组），每个包含 toolName、arguments、reason |
| `String analyzePerTool(String toolName, List<Map<String, Object>> pageResults)` | 阶段1：对单个工具的全部翻页数据做独立分析，输出结构化分析摘要 |
| `String analyzeCross(String question, List<String> perToolAnalyses)` | 阶段2：合并各工具独立分析，交叉分析输出最终答案 |

#### 3.1.2 McpAdapter 新增分页方法

```java
PaginationResult callToolWithPagination(String token, String toolName, Map<String, Object> args)
```

- 首次调用 `page=0, pageSize=100`
- 从返回 JSON 提取 `totalCount`，计算总页数
- 循环调用直到全部数据拉取完毕
- 每次调用返回的原始响应记录到 `PaginationResult.pages`
- 单页失败不中断，记录失败状态后继续
- 默认 pageSize=100，可通过 args 中的 `pageSize` 覆盖

PaginationResult:
```java
record PaginationResult(
    List<PageData> pages,      // 每页数据
    int totalCount,            // 总条数
    int totalPages,            // 总页数
    int successPages,          // 成功页数
    int failedPages            // 失败页数
) {}
record PageData(
    int page,
    int pageSize,
    String status,             // SUCCEEDED / FAILED
    Map<String, Object> rawRequest,
    Map<String, Object> rawResponse,
    String error,
    long latencyMs
) {}
```

#### 3.1.3 AgentOrchestrator 主流程重构

当前 `process()` 中第 103 行 `processLegacy()` 改造为 `processWithDeepSeekPlan()`：

```
1. resolveContext() - 解析用户/项目/Token
2. loadAvailableTools() - 加载可用 MCP 工具列表
3. deepSeekClient.planMultiTool(question, availableTools) → plan
4. 构建 ChainTraceBuilder
5. 对 plan.toolCalls × context.tokens 循环：
   a. mcpAdapter.callToolWithPagination(token, tool, args) → paginationResult
   b. 记录每页到 ChainTraceBuilder
6. 阶段1: 对每个工具的所有页面结果 → deepSeekClient.analyzePerTool()
7. 阶段2: deepSeekClient.analyzeCross(question, perToolAnalyses) → finalAnswer
8. chainTraceService.save(chainTrace)
9. feishuClient.replyAnswerCard() + audit 持久化
```

#### 3.1.4 新增 ChainTraceService

```java
@Service
public class ChainTraceService {
    void save(String requestId, ChainTrace trace);
    ChainTrace load(String requestId);
    
    // ChainTrace 包含:
    // - nodes: List<Node> (plan/工具调用/分析 等步骤)
    // - edges: List<Edge> (节点间连线)
    // - summary: Summary (totalMcpCalls, totalPages, latency, toolsUsed)
}
```

#### 3.1.5 审计日志增强

- `agent_tool_call_log` 表新增 `page` 和 `page_size` 字段
- 新增 `agent_chain_trace` 表存储完整链调 JSON

### 3.2 数据库

V6 Flyway 迁移新增表：

```sql
create table agent_chain_trace (
    id bigint auto_increment primary key,
    request_id varchar(64) not null,
    trace_data json not null comment '完整链调数据',
    created_at timestamp default current_timestamp,
    index idx_request_id (request_id)
);

-- agent_tool_call_log 新增分页字段
alter table agent_tool_call_log
    add column page int default 0,
    add column page_size int default 100,
    add column total_count int default 0;
```

### 3.3 Admin-web 前端

#### 3.3.1 ChainTraceView.vue（新文件）

- **流程图区**: 使用 mermaid npm 包渲染
  - 蓝色节点 = DeepSeek 模型调用 (plan/analyze)
  - 绿色节点 = MCP 工具调用 (成功)
  - 红色节点 = MCP 工具调用 (失败)
  - 按项目分组，按页码编号
- **点击节点**: 弹出 Modal 展示原始 JSON 输入/输出（长文本折叠）
- **汇总区**: 表格列出每个工具的调用概况（总页数、成功/失败、耗时）
- **路由**: `/admin/chain-trace/:requestId`

#### 3.3.2 AgentTasksView 改动

- 操作列新增「链调详情」按钮
- 跳转到 ChainTraceView

#### 3.3.3 新增依赖

```json
{
  "mermaid": "^10.x"
}
```

### 3.4 Python agent-service

改动最小化，仅 `models.py` 中 ToolCall 新增 pagination 可选字段：

```python
class ToolCall(BaseModel):
    toolName: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    projectIds: list[str] = Field(default_factory=list)
    reason: str | None = None
    pagination: dict[str, Any] | None = None  # {pageSize, maxPages}
```

---

## 4. 数据模型

### 4.1 ChainTrace JSON 结构

```json
{
  "requestId": "uuid",
  "nodes": [
    {
      "id": "plan",
      "type": "model_call",
      "label": "DeepSeek 工具规划",
      "modelName": "deepseek-chat",
      "input": "用户问题 + 可用工具列表",
      "output": "[{toolName, arguments, reason}]",
      "latencyMs": 850,
      "status": "SUCCEEDED"
    },
    {
      "id": "tool_query_tasks_projA_p0",
      "type": "mcp_call",
      "label": "query_tasks (项目A, 第1页)",
      "toolName": "query_tasks",
      "projectId": "proj-001",
      "projectName": "示范项目A",
      "page": 0,
      "pageSize": 100,
      "request": {"jsonrpc": "2.0", "method": "tools/call", "params": {"name": "query_tasks", "arguments": {"offset": 0, "limit": 100}}},
      "response": {"totalCount": 250, "items": [...]},
      "latencyMs": 420,
      "status": "SUCCEEDED"
    },
    {
      "id": "analyze_per_tool_tasks",
      "type": "model_call",
      "label": "阶段1: 分析 query_tasks 结果",
      "modelName": "deepseek-chat",
      "input": "query_tasks 全量数据（3页, 250条）",
      "output": "结构化分析摘要...",
      "latencyMs": 1200,
      "status": "SUCCEEDED"
    },
    {
      "id": "analyze_cross",
      "type": "model_call",
      "label": "阶段2: 交叉分析",
      "modelName": "deepseek-chat",
      "input": "query_tasks 分析 + query_project_health 分析 + 用户原始问题",
      "output": "最终答案...",
      "latencyMs": 1800,
      "status": "SUCCEEDED"
    }
  ],
  "edges": [
    {"from": "plan", "to": "tool_query_tasks_projA_p0"},
    {"from": "tool_query_tasks_projA_p0", "to": "tool_query_tasks_projA_p1"},
    {"from": "tool_query_tasks_projA_p1", "to": "tool_query_tasks_projA_p2"},
    {"from": "tool_query_tasks_projA_p2", "to": "analyze_per_tool_tasks"},
    {"from": "plan", "to": "tool_query_health_projA_p0"},
    {"from": "tool_query_health_projA_p0", "to": "analyze_per_tool_health"},
    {"from": "analyze_per_tool_tasks", "to": "analyze_cross"},
    {"from": "analyze_per_tool_health", "to": "analyze_cross"}
  ],
  "summary": {
    "totalMcpCalls": 4,
    "totalPages": 4,
    "totalLatencyMs": 4270,
    "toolsUsed": ["query_tasks", "query_project_health"],
    "projectsQueried": ["proj-001"]
  }
}
```

### 4.2 分页参数约定

MCP 工具请求参数：
```json
{
  "offset": 0,
  "limit": 100
}
```

MCP 工具返回结构（约定）：
```json
{
  "totalCount": 250,
  "items": [...]
}
```

---

## 5. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `backend/.../deepseek/DeepSeekClient.java` | 修改 | + `planMultiTool()`, `analyzePerTool()`, `analyzeCross()` |
| `backend/.../mcp/McpAdapter.java` | 修改 | + `callToolWithPagination()`, + `PaginationResult` record |
| `backend/.../agent/AgentOrchestrator.java` | 修改 | 重构 processLegacy → processWithDeepSeekPlan |
| `backend/.../agent/AgentServiceDtos.java` | 修改 | ToolCall + pagination 字段 |
| `backend/.../audit/ChainTraceService.java` | 新增 | 链调 JSON 构建与持久化 |
| `backend/.../audit/AuditService.java` | 修改 | writeTool + page/pageSize/totalCount |
| `backend/.../admin/AdminController.java` | 修改 | + GET `/api/admin/chain-trace/{requestId}` |
| `backend/.../db/V6__chain_trace.sql` | 新增 | 数据库迁移 |
| `admin-web/src/views/ChainTraceView.vue` | 新增 | Mermaid 流程图 + 数据明细 |
| `admin-web/src/views/AgentTasksView.vue` | 修改 | + 链调详情按钮 |
| `admin-web/src/api/admin.ts` | 修改 | + getChainTrace API |
| `admin-web/src/router/index.ts` | 修改 | + chain-trace 路由 |
| `admin-web/package.json` | 修改 | + mermaid 依赖 |
| `agent-service/app/models.py` | 修改 | ToolCall + pagination 字段 |

---

## 6. 自检清单

- [ ] DeepSeek planMultiTool prompt 需要明确约束输出格式（JSON 数组）
- [ ] 分页失败不中断整体流程，记录失败状态
- [ ] 分页死循环保护：maxPages 上限（默认 50 页）
- [ ] Mermaid 节点过多时（>50 个）自动折叠分页节点为子图
- [ ] DeepSeek API key 不可用时降级为启发式多工具规划
- [ ] 链调 JSON 大小限制（单节点 response 超过 10KB 截断存储）
- [ ] 与现有 `processWithAgentService()` 路径不冲突，仅在 DeepSeek 路径生效
