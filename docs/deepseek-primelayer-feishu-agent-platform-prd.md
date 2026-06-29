# DeepSeek + Primelayer MCP + 飞书 Agent 平台 PRD

## 1. 文档信息

- 文档名称：DeepSeek + Primelayer MCP + 飞书 Agent 平台 PRD
- 当前版本：v0.2
- 日期：2026-06-29
- 阶段：MVP 设计与技术方案确认
- 目标形态：先建设带管理后台的 Agent Gateway MVP，后续演进为通用 Agent 平台

## 2. 背景

当前希望打通 Primelayer 与飞书，使 Primelayer 的项目数据、代办数据和 MCP 能力能够在飞书中被自然语言查询、分析和推送。

业务侧关键事实：

- 一个员工同时拥有飞书账号和 Primelayer 账号。
- 飞书侧一期使用 `open_id` 作为用户身份主键。
- MCP token 跟随 Primelayer 人员账号和项目走。
- 同一个 Primelayer 用户在不同项目下可能拥有不同 MCP token。
- 飞书侧需要支持私聊和群聊两种使用方式。
- 群聊场景必须使用提问者个人身份与 token，不能使用群共享 token。
- 底层大模型使用 DeepSeek。
- 一期采用 Java / Spring Boot 建设独立后端服务。
- 一期提供 Vue 3 + Ant Design Vue 简单 Web 管理后台。

平台需要维护的核心身份与权限链路：

```text
feishu_open_id
  -> primelayer_user_id
    -> project_id
      -> mcp_token
```

## 3. 产品目标

### 3.1 一期目标

建设带管理后台的 Agent Gateway MVP，实现：

- 飞书机器人私聊和群聊接入。
- 飞书用户与 Primelayer 用户绑定。
- 管理员在 Web 后台导入项目级 MCP token。
- 管理员在 Web 后台绑定飞书群与 Primelayer 项目。
- DeepSeek 理解用户问题并生成结构化工具调用计划。
- Agent Gateway 根据用户、项目和 token 调用 Primelayer MCP。
- 支持私聊单项目查询、私聊跨项目查询、群聊项目上下文查询。
- 支持 RabbitMQ 异步任务执行与飞书异步回复。
- 支持任务状态查看和完整审计日志。

### 3.2 后续目标

MVP 稳定后演进为通用 Agent 平台：

- MCP 工具配置后台。
- Agent Prompt 模板管理。
- Token 管理增强与密钥迁移。
- 多入口接入，例如 Web、企业微信、钉钉。
- 多步工具调用。
- 长期任务代理。
- 项目风险主动监控。
- 自动日报、周报、项目健康度分析。

## 4. 用户角色

### 4.1 项目负责人

核心诉求：

- 查询负责项目的代办、风险、逾期趋势。
- 跨项目查看任务堆积和负责人负载。
- 在飞书群中快速获取当前项目状态。

### 4.2 一线执行人

核心诉求：

- 查询自己的代办。
- 查看任务详情。
- 快速跳转到 Primelayer 处理任务。

### 4.3 管理员

核心诉求：

- 登录 Web 管理后台。
- 维护飞书 `open_id` 与 Primelayer 用户绑定。
- 导入或替换项目级 MCP token。
- 绑定飞书群与 Primelayer 项目。
- 查看 Agent 任务状态、审计日志和调用状态。

## 5. 已确认技术决策

| 决策项 | 一期选择 |
| --- | --- |
| 飞书用户主键 | `open_id` |
| 后端技术栈 | Java 17 + Spring Boot |
| 前端管理端 | Vue 3 + Ant Design Vue |
| 数据库 | MySQL 8 |
| 数据库迁移 | Flyway |
| 异步执行 | RabbitMQ |
| MCP 接入 | 标准 MCP 协议，Streamable HTTP |
| 大模型 | DeepSeek |
| 模型计划契约 | JSON Schema 风格结构化输出 |
| 后台认证 | 账号密码登录 |
| MCP token 加密 | AES-GCM |
| 加密主密钥 | MVP 存数据库配置，生产前建议迁移到环境变量或 KMS |

## 6. 核心场景

### 6.1 私聊查询单项目数据

用户私聊机器人：

```text
帮我看一下 A 项目本周有哪些逾期代办？
```

系统流程：

1. 飞书事件入口接收消息并识别 `open_id`。
2. 根据 `open_id` 查询 Primelayer 用户绑定关系。
3. DeepSeek 识别查询意图、项目线索和 MCP 工具调用计划。
4. Token Resolver 根据项目线索匹配该用户项目 token。
5. Agent Gateway 校验工具、参数、用户、项目和 token。
6. MCP Adapter 通过 Streamable HTTP 调用 Primelayer MCP。
7. DeepSeek 基于 MCP 返回数据生成总结。
8. 飞书异步回复最终结果。
9. 审计日志记录完整链路。

### 6.2 私聊跨项目查询

用户私聊机器人：

```text
帮我分析一下我所有项目的逾期趋势。
```

系统流程：

1. 根据 `open_id` 查询 Primelayer 用户。
2. DeepSeek 将项目范围识别为 `all_accessible_projects`。
3. Token Resolver 查询该用户所有有效项目 token。
4. 系统按最大项目数限制遍历项目。
5. 对每个项目分别调用 MCP。
6. 单个项目失败不影响整体分析。
7. DeepSeek 汇总各项目结果。
8. 回复中说明数据覆盖范围、失败项目和未覆盖项目。

### 6.3 群聊项目查询

用户在飞书项目群中 @ 机器人：

```text
@机器人 当前项目最大的风险是什么？
```

系统流程：

1. 识别飞书群 `chat_id`。
2. 查询该群绑定的 Primelayer 项目。
3. 识别提问者 `open_id`。
4. 查询提问者绑定的 Primelayer 用户。
5. 查询提问者在该项目下的 MCP token。
6. 如果 token 存在，则调用 MCP。
7. 如果 token 不存在，则提示无当前项目访问配置。
8. 回复分析结果。

## 7. 功能需求

### 7.1 飞书机器人接入

支持：

- 私聊文本消息接收。
- 群聊 @ 机器人文本消息接收。
- 飞书 URL challenge 处理。
- 异步消息回复。
- 文本消息回复。
- 后续扩展卡片消息。

要求：

- 收到复杂查询后先回复“正在分析，请稍等”。
- 分析完成后再次发送最终结果。
- 群聊中只处理 @ 机器人的消息。
- 通过 `message_id` 幂等，避免重复事件重复处理。

### 7.2 Web 管理后台

一期后台功能：

- 管理员账号密码登录。
- 用户绑定管理。
- 项目 MCP token 导入、替换、禁用。
- 飞书群项目绑定。
- Agent 任务状态查看。
- 审计日志查看。

后台约束：

- token 页面不允许查看明文 token。
- token 保存后只展示 token hash 后缀。
- 后台 API 需要 Bearer token。
- 默认管理员账号仅用于初始化，正式部署需修改默认密码。

### 7.3 用户绑定

字段建议：

| 字段 | 说明 |
| --- | --- |
| id | 绑定记录 ID |
| feishu_open_id | 飞书用户 open_id，唯一 |
| primelayer_user_id | Primelayer 用户 ID |
| primelayer_user_name | Primelayer 用户名称 |
| status | 绑定状态 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

规则：

- 一期由管理员在后台维护绑定关系。
- 禁用状态用户不可查询 Primelayer 数据。
- `feishu_open_id` 为全局唯一绑定键。

### 7.4 项目级 MCP Token 管理

字段建议：

| 字段 | 说明 |
| --- | --- |
| id | token 记录 ID |
| primelayer_user_id | Primelayer 用户 ID |
| project_id | Primelayer 项目 ID |
| project_name | 项目名称 |
| mcp_token_ciphertext | 加密后的 MCP token |
| token_hash_suffix | token hash 后缀，用于排查 |
| token_status | token 状态 |
| imported_by | 导入管理员 |
| imported_at | 导入时间 |
| last_used_at | 最近使用时间 |

要求：

- token 由管理员导入或替换。
- token 长期有效。
- 数据库不保存明文 token。
- 审计日志不得输出完整 token。
- token 调用失败时记录失败原因。
- 同一 `primelayer_user_id + project_id` 只保留一条有效配置。

### 7.5 群聊项目绑定

字段建议：

| 字段 | 说明 |
| --- | --- |
| id | 绑定记录 ID |
| feishu_chat_id | 飞书群 ID |
| project_id | Primelayer 项目 ID |
| project_name | 项目名称 |
| status | 绑定状态 |
| created_by | 创建管理员 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

规则：

- 一个飞书群一期只绑定一个 Primelayer 项目。
- 群聊查询默认使用群绑定项目。
- 未绑定项目的群聊，需要提示管理员先完成绑定。
- 群聊不使用群共享 token，只使用提问者个人 token。

## 8. Agent Gateway 技术方案

### 8.1 总体架构

```text
飞书用户 / 飞书群
        |
        v
Feishu Adapter
        |
        v
Agent Task Service
        |
        v
RabbitMQ
        |
        v
Agent Worker
        |
        +--> Identity / User Binding
        +--> Token Resolver
        +--> DeepSeek Client
        +--> MCP Tool Registry
        +--> Primelayer MCP Adapter
        +--> Audit Service
        |
        v
Feishu Async Reply

Admin Web
        |
        v
Admin API
        |
        +--> User Binding
        +--> Project MCP Token
        +--> Chat Project Binding
        +--> Agent Task
        +--> Audit Log
```

### 8.2 异步任务链路

飞书事件入口职责：

- 验证并解析飞书事件。
- 处理 URL challenge。
- 提取 `message_id`、`open_id`、`chat_id`、`chat_type`、文本内容。
- 基于 `message_id` 做幂等。
- 写入 `agent_task`。
- 投递 RabbitMQ。
- 快速返回，避免飞书回调超时。

Agent Worker 职责：

- 将任务状态从 `PENDING` 更新为 `RUNNING`。
- 发送“正在分析，请稍等”。
- 调用 DeepSeek 生成结构化计划。
- 解析用户、项目和 token。
- 校验并执行 MCP 工具调用。
- 调用 DeepSeek 汇总结果。
- 发送飞书最终回复。
- 写入审计日志。
- 将任务状态更新为 `SUCCEEDED` 或 `FAILED`。

### 8.3 DeepSeek 调用契约

DeepSeek 只负责理解和生成计划，不直接访问数据库、MCP 或飞书。

结构化计划字段：

```json
{
  "intent": "project_query",
  "projectScope": "single_project",
  "projectHints": ["A 项目"],
  "toolCalls": [
    {
      "toolName": "primelayer.query_tasks",
      "arguments": {
        "question": "帮我看一下 A 项目本周有哪些逾期代办？"
      }
    }
  ],
  "needClarification": false,
  "clarificationQuestion": null,
  "answerStyle": "normal"
}
```

`projectScope` 取值：

- `single_project`
- `current_chat_project`
- `all_accessible_projects`
- `unknown`

约束：

- DeepSeek 输出只作为计划建议。
- Gateway 必须校验工具名、参数、用户权限和项目 token。
- DeepSeek 不能接收 MCP token 明文。
- DeepSeek 不能直接决定越权项目查询。

### 8.4 Token Resolver 规则

私聊：

- 如果问题指定项目，只使用该项目 token。
- 如果问题未指定项目，但短期上下文存在项目，可使用上下文项目。
- 如果问题是跨项目查询，遍历该用户所有有效项目 token。
- 如果无法判断项目且不是跨项目问题，提示用户指定项目。

群聊：

- 必须先绑定项目。
- 使用提问者在该项目下的 MCP token。
- 不使用群内其他成员 token。
- 不使用群共享 token。

跨项目：

- 单次查询最大项目数由系统配置控制。
- 单项目失败不影响整体结果。
- 回复中必须说明失败项目和未覆盖项目。

### 8.5 MCP Tool Registry

一期采用静态工具注册。

工具定义包含：

| 字段 | 说明 |
| --- | --- |
| tool_name | 工具名称 |
| description | 工具描述 |
| input_schema | 参数 schema |
| permission_tag | 权限标签 |
| enabled | 是否启用 |
| timeout_ms | 超时时间 |

规则：

- 只有注册且启用的工具可被调用。
- DeepSeek 输出不存在工具名时拒绝执行。
- DeepSeek 输出非法参数时拒绝执行。
- 工具调用必须绑定 `project_id` 和 `primelayer_user_id`。

### 8.6 Primelayer MCP Adapter

MCP Adapter 使用标准 MCP Streamable HTTP 调用 Primelayer MCP Server。

职责：

- 封装 `tools/call`。
- 注入项目级 MCP token。
- 处理 MCP 超时、失败、空结果。
- 标准化 MCP 返回结构。
- 屏蔽底层 MCP API 差异。
- 记录每次工具调用日志。

请求形态示例：

```json
{
  "jsonrpc": "2.0",
  "id": "request-id",
  "method": "tools/call",
  "params": {
    "name": "primelayer.query_tasks",
    "arguments": {
      "project_id": "project_001",
      "primelayer_user_id": "pl_user_001",
      "question": "本周有哪些逾期代办？"
    }
  }
}
```

## 9. 数据模型

### 9.1 admin_user

```sql
CREATE TABLE admin_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 9.2 user_binding

```sql
CREATE TABLE user_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  feishu_open_id VARCHAR(128) NOT NULL UNIQUE,
  primelayer_user_id VARCHAR(128) NOT NULL,
  primelayer_user_name VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 9.3 project_mcp_token

```sql
CREATE TABLE project_mcp_token (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  primelayer_user_id VARCHAR(128) NOT NULL,
  project_id VARCHAR(128) NOT NULL,
  project_name VARCHAR(256) NOT NULL,
  mcp_token_ciphertext TEXT NOT NULL,
  token_hash_suffix VARCHAR(32),
  token_status VARCHAR(32) NOT NULL,
  imported_by VARCHAR(128),
  imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at TIMESTAMP NULL,
  UNIQUE KEY uk_project_token_user_project (primelayer_user_id, project_id)
);
```

### 9.4 feishu_chat_project_binding

```sql
CREATE TABLE feishu_chat_project_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  feishu_chat_id VARCHAR(128) NOT NULL UNIQUE,
  project_id VARCHAR(128) NOT NULL,
  project_name VARCHAR(256) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_by VARCHAR(128),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 9.5 agent_task

```sql
CREATE TABLE agent_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(128) NOT NULL UNIQUE,
  feishu_message_id VARCHAR(128) NOT NULL UNIQUE,
  feishu_open_id VARCHAR(128) NOT NULL,
  feishu_chat_id VARCHAR(128),
  chat_type VARCHAR(32) NOT NULL,
  message_text TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMP NULL,
  finished_at TIMESTAMP NULL
);
```

### 9.6 审计日志

审计日志拆分为：

- `agent_audit_log`：请求主日志。
- `agent_tool_call_log`：MCP 工具调用明细。
- `agent_model_call_log`：模型调用明细。

要求：

- 不记录完整明文 token。
- 可以记录 token ID 或 token hash 后缀。
- 跨项目查询必须记录每个项目的调用状态。
- 飞书异步回复能追踪到原始问题和最终回答。

## 10. 接口草案

### 10.1 飞书事件入口

```http
POST /api/feishu/events
```

用途：

- 接收飞书消息事件。
- 处理 challenge。
- 解析私聊或群聊消息。
- 创建异步任务。

### 10.2 管理员登录

```http
POST /api/admin/login
```

请求：

```json
{
  "username": "admin",
  "password": "admin123"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "token": "admin-token",
    "expiresInSeconds": 28800
  }
}
```

### 10.3 用户绑定

```http
GET /api/admin/user-bindings
POST /api/admin/user-bindings
```

请求：

```json
{
  "feishuOpenId": "ou_xxx",
  "primelayerUserId": "pl_user_001",
  "primelayerUserName": "张三",
  "status": "ACTIVE"
}
```

### 10.4 项目 MCP Token

```http
GET /api/admin/project-tokens
POST /api/admin/project-tokens
```

请求：

```json
{
  "primelayerUserId": "pl_user_001",
  "projectId": "project_001",
  "projectName": "A 项目",
  "mcpToken": "mcp_xxx",
  "tokenStatus": "ACTIVE"
}
```

### 10.5 飞书群项目绑定

```http
GET /api/admin/chat-project-bindings
POST /api/admin/chat-project-bindings
```

请求：

```json
{
  "feishuChatId": "oc_xxx",
  "projectId": "project_001",
  "projectName": "A 项目",
  "status": "ACTIVE"
}
```

### 10.6 审计与任务

```http
GET /api/admin/audit-logs
GET /api/admin/agent-tasks
```

用途：

- 管理员查看最近请求链路。
- 管理员查看 Agent 任务状态、失败原因和完成时间。

## 11. 回复格式

机器人回答建议采用：

```text
结论：
...

关键数据：
- ...
- ...

数据范围：
- 项目：...
- 时间：...
- 未覆盖：...

来源：
- Primelayer 项目链接 / 代办链接
```

群聊回答需要更短，避免刷屏。

复杂分析可提供：

- 简版结论。
- 详情链接。
- “继续追问”提示。

## 12. 异常处理

### 12.1 用户未绑定

```text
你还没有绑定 Primelayer 账号，请联系管理员完成绑定。
```

### 12.2 群未绑定项目

```text
当前飞书群还没有绑定 Primelayer 项目，请联系管理员完成群项目绑定。
```

### 12.3 用户没有项目 token

```text
你当前没有该 Primelayer 项目的 MCP 访问配置，请联系管理员确认项目 token。
```

### 12.4 无法判断项目

```text
我还无法判断你要查询哪个项目，请补充项目名称。
```

### 12.5 MCP 调用失败

```text
Primelayer 数据查询失败，我已经记录错误。你可以稍后重试，或联系管理员查看日志。
```

### 12.6 DeepSeek 调用失败

```text
分析服务暂时不可用，请稍后重试。
```

## 13. 非功能需求

### 13.1 安全

- MCP token 必须加密存储。
- DeepSeek 不能直接访问 MCP。
- DeepSeek prompt 中不得注入完整 token。
- 所有工具调用必须经过 Agent Gateway 校验。
- 群聊中使用提问者身份查询，不使用群身份查询。
- A 用户不能使用 B 用户 token。
- 后台默认密码必须在生产部署前修改。

### 13.2 稳定性

- DeepSeek 调用需要设置超时。
- MCP 调用需要设置超时。
- 飞书发送失败需要重试。
- RabbitMQ 消费失败进入重试或死信队列。
- 跨项目查询中单个项目失败不应导致整体失败。
- 结果中需要说明失败或未覆盖项目。

### 13.3 可观测性

- 记录请求总耗时。
- 记录 DeepSeek 调用耗时。
- 记录 MCP 调用耗时。
- 记录飞书发送状态。
- 支持按用户、项目、工具查询日志。
- 后台展示任务状态和最近审计日志。

### 13.4 成本控制

一期上线前至少加入：

- 单用户并发限制。
- 单群并发限制。
- 单次查询最大 MCP 调用项目数。
- DeepSeek 超时时间。
- MCP 超时时间。
- 最大输入数据量限制。

## 14. 验收标准

### 14.1 功能验收

- 管理员能登录后台。
- 管理员能维护用户绑定。
- 管理员能导入或替换项目 MCP token，且后台不回显明文 token。
- 管理员能绑定飞书群和 Primelayer 项目。
- 用户私聊查询指定项目时，平台能选中正确项目 token。
- 用户私聊查询所有项目时，平台能遍历该用户所有项目 token 并汇总。
- 群聊绑定项目后，@ 机器人能按当前群项目查询。
- 用户没有当前项目 token 时，机器人返回无权限提示。
- MCP 调用失败时，机器人能返回清晰降级说明。

### 14.2 权限验收

- A 用户不能使用 B 用户的项目 token。
- 同一用户不能查询未导入 token 的项目。
- 群聊不会因为其他成员有权限而扩大提问者权限。
- 工具调用必须经过 schema 校验。
- DeepSeek 输出的非法工具或非法参数不会被执行。

### 14.3 审计验收

- 每次请求记录飞书用户、Primelayer 用户、项目、工具、参数、耗时、状态。
- 审计日志不直接明文展示完整 MCP token。
- 跨项目查询能追踪每个项目的调用结果。
- 飞书异步回复能追踪到原始问题和最终回答。
- 后台能查看最近任务状态和审计日志。

## 15. 分阶段计划

### Phase 1：Agent Gateway MVP

交付内容：

- Spring Boot 服务骨架。
- Vue 3 管理后台。
- MySQL Flyway 数据库迁移。
- 飞书事件入口。
- RabbitMQ 异步任务。
- DeepSeek Client。
- Primelayer MCP Adapter。
- Identity / User Binding。
- Project Token Service。
- Token Resolver。
- 静态 MCP Tool Registry。
- 私聊和群聊查询。
- 异步回复。
- 审计日志。

### Phase 2：平台化增强

交付内容：

- Token 管理后台增强。
- MCP 工具配置后台。
- Agent Prompt 模板管理。
- 回答质量反馈。
- 项目级使用统计。
- DeepSeek 调用统计。
- 工具调用回放与调试。
- 加密主密钥迁移到环境变量或 KMS。

### Phase 3：高级 Agent 能力

交付内容：

- 多步工具调用。
- 长期任务代理。
- 主动项目风险监控。
- 自动日报和周报。
- 项目健康度 Agent。
- 跨项目趋势分析 Agent。
- 多入口接入。

## 16. 当前不做事项

第一阶段不做：

- 不做完整 Agent Studio。
- 不做可视化 Agent 编排器。
- 不做多租户。
- 不做飞书内写回 Primelayer。
- 不做任务完成、延期、转派等写操作。
- 不做长期记忆。
- 不做自动学习用户偏好。
- 不做复杂额度和计费系统。
- 不做群共享 token。

## 17. 关键风险

### 17.1 MCP token 安全风险

风险：

- token 跟随人员和项目，数量可能较多。
- 一旦泄露，可能造成项目数据暴露。
- MVP 将加密主密钥存放在数据库配置中，密文和密钥同库存在安全弱点。

缓解：

- AES-GCM 加密存储。
- 日志脱敏。
- 后台不回显明文 token。
- 严格限制管理员权限。
- 审计 token 使用记录。
- 生产前将主密钥迁移到环境变量或 KMS。

### 17.2 群聊数据越权风险

风险：

- 群聊中可能出现不同权限用户。

缓解：

- 始终使用提问者的 Primelayer 身份和项目 token。
- 不使用群共享 token。
- 无 token 时返回无权限提示。

### 17.3 DeepSeek 幻觉风险

风险：

- 模型可能生成不存在的数据或错误结论。

缓解：

- 回答必须基于 MCP 返回数据。
- 回答必须包含数据范围和来源。
- 对无数据场景明确说明无数据。
- 工具调用计划必须由 Gateway 强校验。

### 17.4 跨项目查询性能风险

风险：

- 用户项目多时，MCP 调用量大，响应慢。

缓解：

- RabbitMQ 异步执行。
- 设置最大项目数。
- 单项目失败不影响整体结果。
- 后续增加缓存和统计预聚合。

## 18. 默认假设

- DeepSeek 是底层大模型。
- Java / Spring Boot 是第一期后端技术栈。
- Vue 3 + Ant Design Vue 是一期后台技术栈。
- 第一阶段服务单组织，不做多租户。
- MCP token 由管理员导入。
- MCP token 长期有效。
- MCP token 粒度是 Primelayer 用户 + 项目。
- 私聊跨项目查询默认遍历用户已绑定 token 的项目。
- 群聊查询默认使用群绑定项目。
- 第一阶段不在飞书内写回 Primelayer。
- 第一阶段不做完整 Agent Studio，只做可治理的 Agent Gateway。
