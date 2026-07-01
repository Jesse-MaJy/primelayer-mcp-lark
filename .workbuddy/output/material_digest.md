# AICoding 架构设计 · 资料摘要

> 本文档做一件事：**精读主理人转交的全部原始资料，逐份、逐章节做出摘要**——后面任何人拿到这份摘要，都能通过章节号快速定位回原始文件的对应位置。

> 上游输入：主理人转交的全部原始资料（markdown）；
> 产出者：`knowledge-ingest-engineer`（知识摄入工程师 - 闻资料），经 G1 校验与人工审核通过后交付。

---

## 0. 元信息

```yaml
标题: Lark Connect Agent Gateway - 资料摘要 v1.0
版本: v1.0
状态: Draft
创建日期: 2025-07-01
整理人: knowledge-ingest-engineer
审核人:
  - team-lead（主理人）

原始资料清单:
  - README.md: 项目概述与本地运行说明
  - 项目分析报告.md: 项目定位/模块/数据流/问题/评价的详细分析
  - docs/deepseek-primelayer-feishu-agent-platform-prd.md: PRD v0.2 完整产品需求
```

| 版本 | 日期 | 作者 | 变更内容 |
| --- | --- | --- | --- |
| v1.0 | 2025-07-01 | knowledge-ingest-engineer | 初稿，覆盖三份原始资料全文 |

---

## 1. 资料清单

> 列出全部原始资料，每份标注解析状态。解析失败或跳过的必须注明原因。

| 编号 | 文件名 | 类型 | 来源 | 解析状态 | 说明 |
| --- | --- | --- | --- | --- | --- |
| D1 | README.md | markdown | 项目根目录 | 已解析 | 项目概述、模块说明、本地运行步骤与安全说明 |
| D2 | 项目分析报告.md | markdown | 项目根目录 | 已解析 | 项目定位、三大模块、核心数据流、已知问题与总体评价 |
| D3 | docs/deepseek-primelayer-feishu-agent-platform-prd.md | markdown | docs 目录 | 已解析 | PRD v0.2 完整产品需求，含技术方案、数据模型、接口草案、分阶段计划 |

---

## 2. 资料内容摘要

> 逐份文档按自身章节结构做摘要。每条摘要标注章节号（`D编号，§章节`），后面任何人想核实某个点，直接定位回原文对应位置即可。

### D1：README.md

> 项目根目录的快速入门文件，描述模块组成、本地运行步骤与安全说明 — 来源：项目根目录

| 章节 | 内容摘要 |
| --- | --- |
| D1，§概述 | DeepSeek + Primelayer MCP + 飞书 Agent Gateway MVP；Java 仍为飞书事件、项目权限、MCP Token 处理、MCP 调用、审计的安全边界；agent-service 为可选 Python 智能层，负责技能路由、MCP 工具规划与答案摘要 |
| D1，§Modules | 三模块：`backend`（Java 17 / Spring Boot API、RabbitMQ worker、MySQL schema、DeepSeek planner、MCP Streamable HTTP adapter）；`agent-service`（Python FastAPI + LangChain/LangGraph，负责业务技能路由与 MCP 答案规划）；`admin-web`（Vue 3 + Ant Design Vue 管理后台） |
| D1，§Local Run | 本地运行需 MySQL 8 + RabbitMQ；数据库名 `lark_connect_agent`；`.env.example` 复制为 `.env` 并填入 `MYSQL_PASSWORD`、`DEEPSEEK_API_KEY`、飞书凭证；后端启动 `mvn spring-boot:run`，前端启动 `npm install && npm run dev` |
| D1，§Default Admin | 默认管理员账号：用户名 `admin`，密码 `admin123`，通过 `.env` 配置 |
| D1，§Security Notes | MCP Token AES-GCM 主密钥存储在 `system_config` 表（Phase 1 选定方案），密文静态加密但非生产级密钥隔离，上生产前需迁移到环境变量或 KMS |
| D1，§Spring Boot Env Loading | Spring Boot 按以下顺序加载 env 文件：1）`backend/src/main/resources/.env`（打包时嵌入）；2）工作目录 `.env`；3）项目根 `../.env`；后者覆盖前者，生产可在 jar 旁挂载外部 `.env` 而无需重新构建 |

### D2：项目分析报告.md

> 2026-06-30 生成的项目分析报告，涵盖定位、三大模块、核心数据流、已知问题与总体评价 — 来源：项目根目录

| 章节 | 内容摘要 |
| --- | --- |
| D2，§一·项目定位 | Lark Connect Agent Gateway：打通 Primelayer（地产科技项目管理系统）与飞书，让飞书用户用自然语言查询项目数据、代办、风险的 Agent 网关 MVP。核心身份与权限链路：`feishu_open_id → primelayer_user_id → project_id → mcp_token`；底层大模型 DeepSeek；一期目标为带管理后台的 Agent Gateway MVP（私聊/群聊、用户绑定、项目 Token 管理、异步任务、完整审计）；后续演进而通用 Agent 平台（多入口、多步工具、主动监控、自动周报） |
| D2，§二·三大模块 | 三模块概述：`backend`（Java 17 + Spring Boot 3.3.7，安全边界）；`agent-service`（Python 3.10+ / FastAPI / LangChain / LangGraph，智能层，只收脱敏上下文不碰 Token）；`admin-web`（Vue 3 + Ant Design Vue 4 + Vite 6 + TS + Pinia，管理后台） |
| D2，§二.1·backend | 关键依赖：spring-boot-starter（web/amqp/jdbc/security/validation）、larksuite oapi-sdk 2.4.0、flyway-mysql、mysql-connector-j。包结构 `com.larkconnect.agent`：`token`/`crypto`（Token 解析 + AES-GCM 加解密）、`config`（SecurityConfig/AppProperties/RabbitConfig）、`feishu`（事件解析/FeishuClient/事件控制器/WS Echo Bot）、`admin`（AdminController/AdminService/AdminRepository/AdminTokenFilter/DebugController）、`agent`（IntentRouter/AgentOrchestrator/AgentWorker/AgentServiceClient）、`mcp`（McpAdapter/McpToolRegistry）、`deepseek`（DeepSeekClient/DeepSeekPlan）、`audit`（AuditService）、`common`（Status/ApiResponse/GlobalExceptionHandler） |
| D2，§二.1·backend-DB | 数据库 MySQL 8 + Flyway，V1-V5 迁移，9 张表：`admin_user`（管理员）、`user_binding`（飞书↔Primelayer 用户绑定）、`project_mcp_token`（项目级 MCP Token 密文存储）、`feishu_chat_project_binding`（飞书群↔项目绑定）、`agent_task`（异步任务）、`agent_audit_log`/`agent_tool_call_log`/`agent_model_call_log`（三级审计）、`system_config`（系统配置，含 AES 主密钥） |
| D2，§二.2·agent-service | 核心为 `agent_graph.py` 使用 LangGraph 状态机，5 步流水线：`classify_skill → select_project_scope → plan_tool_calls → summarize_answer → quality_check`。4 个业务技能（`skills.py`，关键词匹配分类）：`project_status_qa`（项目状态问答）、`task_risk_qa`（任务风险问答）、`weekly_report`（日报周报）、`general_mcp_qa`（通用 MCP 兜底）。安全约束：只选只读工具（`get_/list_/query_/search_` 前缀），MCP Token 永不进入此服务，摘要可选模型（DeepSeek），无 API Key 时走确定性摘要。API：`POST /v1/agent/answer`（Planning 阶段不传 toolResults，Summarization 阶段传 toolResults） |
| D2，§二.3·admin-web | 10 个视图：Login、AdminLayout、ProjectTokens、UserBindings、ChatBindings、AgentTasks、AuditLogs、FeishuMessages、TestCenter、PeopleConfig。3 个通用组件：CrudPage、ReadonlyTable、ResultViewer |
| D2，§三·核心数据流 | 飞书消息 → FeishuEventController 接收 → RabbitMQ 异步入队 → AgentWorker 消费 → TokenResolver 解密该用户/项目的 MCP Token → AgentOrchestrator 编排 → 调 agent-service（Planning：技能路由 + 工具计划）← 返回 toolCalls → McpAdapter 携 Token 调 Primelayer MCP（tools/call）→ 工具结果回传 agent-service（Summarization：生成答案）→ FeishuClient 异步回复飞书 + 写审计日志（task/tool/model 三级） |
| D2，§四·项目状态 | Git 仅 1 个 commit「Initial Agent Gateway MVP」，工作区有大量未提交修改（几乎覆盖所有源文件），MVP 之后多轮迭代未提交。PRD v0.2，阶段「MVP 设计与技术方案确认」。`admin-web/dist` 已存在，前端可构建。本地运行需 MySQL 8 + RabbitMQ + 飞书凭证 + DeepSeek Key + Primelayer MCP 端点 |
| D2，§五·P0 问题 | 1）`pom.xml` 编译插件配置错误：`maven-compiler-plugin` 的 source 和 target 写成了 5（Java 5），与 `java.version` 设为 17 严重矛盾，应删除或改为 17。2）MCP Token 主密钥存于 `system_config` 表，README 自述「非生产级密钥隔离」，上生产前必须迁移到环境变量 / KMS |
| D2，§五·P1 问题 | 3）大量改动未提交，建议按功能分批 commit。4）默认管理员凭据 `admin/admin123`，生产必须强改。5）DeepSeek 模型名不一致：`.env.example` 为 `deepseek-v4-pro`，`application.yml` 默认 `deepseek-chat`，需统一（注意 DeepSeek 官方无 v4-pro 型号，疑似笔误）。6）测试覆盖偏低：backend 仅 3 个测试类（TokenResolver/AdminService/DeepSeekClient），agent-service 仅 1 个（test_skills），核心编排与 MCP 适配无测试 |
| D2，§五·P2 问题 | 7）Agent 智能性有限：技能分类与工具选择均为关键词匹配的确定性逻辑，模型摘要可选，MVP 够用但离「通用 Agent 平台」目标尚远。8）`.DS_Store` 散落，建议确认 `.gitignore` 已忽略。9）`DebugController` 暴露调试端点，生产环境应禁用或加权限收敛 |
| D2，§六·总体评价 | 架构清晰、职责分明的 MVP：Java 作安全边界、Python 作智能层的双层分离设计合理，Token 不出后端的约束到位；三级审计齐全；异步化 + 群聊/私聊场景考虑周到。当前最该做：修 pom 编译配置 → 提交存量改动 → 收敛密钥与默认凭据 → 补核心链路测试。功能层面 MVP 已基本成型，下一步是打磨工程化质量 |

### D3：docs/deepseek-primelayer-feishu-agent-platform-prd.md

> PRD v0.2 完整产品需求文档（928 行），涵盖背景、目标、用户角色、技术决策、核心场景、功能需求、技术方案、数据模型、接口草案、非功能需求、验收标准、分阶段计划与风险 — 来源：docs 目录

| 章节 | 内容摘要 |
| --- | --- |
| D3，§1·文档信息 | 文档名称：DeepSeek + Primelayer MCP + 飞书 Agent 平台 PRD；版本 v0.2；日期 2026-06-29；阶段：MVP 设计与技术方案确认；目标形态：先建设带管理后台的 Agent Gateway MVP，后续演进为通用 Agent 平台 |
| D3，§2·背景 | 打通 Primelayer 与飞书，使 Primelayer 的项目数据、代办数据和 MCP 能力能够在飞书中被自然语言查询、分析和推送。关键事实：一个员工同时拥有飞书和 Primelayer 账号；飞书侧一期使用 `open_id` 作为用户身份主键；MCP token 跟随 Primelayer 人员账号和项目走；同一用户在不同项目下可能拥有不同 MCP token；支持私聊和群聊两种方式；群聊必须使用提问者个人身份与 token；底层大模型 DeepSeek；一期采用 Java/Spring Boot 建设独立后端；一期提供 Vue 3 + Ant Design Vue Web 管理后台 |
| D3，§2·身份链路 | 核心身份与权限链路：`feishu_open_id → primelayer_user_id → project_id → mcp_token` |
| D3，§3.1·一期目标 | 建设带管理后台的 Agent Gateway MVP：飞书机器人私聊和群聊接入；飞书用户与 Primelayer 用户绑定；管理员 Web 后台导入项目级 MCP token；管理员 Web 后台绑定飞书群与 Primelayer 项目；DeepSeek 理解用户问题并生成结构化工具调用计划；Agent Gateway 根据用户、项目和 token 调用 Primelayer MCP；支持私聊单项目查询、私聊跨项目查询、群聊项目上下文查询；RabbitMQ 异步任务执行与飞书异步回复；任务状态查看和完整审计日志 |
| D3，§3.2·后续目标 | MVP 稳定后演进为通用 Agent 平台：MCP 工具配置后台、Agent Prompt 模板管理、Token 管理增强与密钥迁移、多入口接入（Web/企业微信/钉钉）、多步工具调用、长期任务代理、项目风险主动监控、自动日报周报项目健康度分析 |
| D3，§4·用户角色 | 三类角色：4.1 项目负责人（查询负责项目的代办/风险/逾期趋势、跨项目查看任务堆积和负责人负载、在飞书群获取当前项目状态）；4.2 一线执行人（查询自己的代办、查看任务详情、快速跳转 Primelayer 处理任务）；4.3 管理员（登录 Web 后台、维护 open_id 与 Primelayer 用户绑定、导入或替换项目级 MCP token、绑定飞书群与 Primelayer 项目、查看 Agent 任务状态/审计日志/调用状态） |
| D3，§5·已确认技术决策 | 飞书用户主键 `open_id`；后端 Java 17 + Spring Boot；前端 Vue 3 + Ant Design Vue；数据库 MySQL 8；数据库迁移 Flyway；异步执行 RabbitMQ；MCP 接入标准 MCP 协议 Streamable HTTP；大模型 DeepSeek；模型计划契约 JSON Schema 风格结构化输出；后台认证账号密码登录；MCP token 加密 AES-GCM；加密主密钥 MVP 存数据库配置，生产前建议迁移到环境变量或 KMS |
| D3，§6.1·私聊查询单项目数据 | 场景：用户私聊「帮我看一下 A 项目本周有哪些逾期代办？」。流程：飞书事件入口接收并识别 open_id → 查询 Primelayer 用户绑定 → DeepSeek 识别查询意图/项目线索/MCP 工具调用计划 → Token Resolver 匹配该用户项目 token → Agent Gateway 校验工具/参数/用户/项目/token → MCP Adapter 通过 Streamable HTTP 调用 Primelayer MCP → DeepSeek 基于返回数据生成总结 → 飞书异步回复 → 审计日志记录完整链路 |
| D3，§6.2·私聊跨项目查询 | 场景：用户私聊「帮我分析一下我所有项目的逾期趋势。」。流程：根据 open_id 查询 Primelayer 用户 → DeepSeek 将项目范围识别为 `all_accessible_projects` → Token Resolver 查询该用户所有有效项目 token → 系统按最大项目数限制遍历项目 → 对每个项目分别调用 MCP → 单个项目失败不影响整体分析 → DeepSeek 汇总各项目结果 → 回复中说明数据覆盖范围、失败项目和未覆盖项目 |
| D3，§6.3·群聊项目查询 | 场景：用户在飞书项目群 @ 机器人「当前项目最大的风险是什么？」。流程：识别飞书群 chat_id → 查询该群绑定的 Primelayer 项目 → 识别提问者 open_id → 查询提问者绑定的 Primelayer 用户 → 查询提问者在该项目下的 MCP token → token 存在则调用 MCP，不存在则提示无当前项目访问配置 → 回复分析结果 |
| D3，§7.1·飞书机器人接入 | 支持：私聊文本消息接收、群聊 @ 机器人文本消息接收、飞书 URL challenge 处理、异步消息回复、文本消息回复、后续扩展卡片消息。要求：收到复杂查询先回复"正在分析，请稍等"，分析完成后再次发送最终结果；群聊中只处理 @ 机器人的消息；通过 `message_id` 幂等避免重复处理 |
| D3，§7.2·Web 管理后台 | 一期功能：管理员账号密码登录、用户绑定管理、项目 MCP token 导入/替换/禁用、飞书群项目绑定、Agent 任务状态查看、审计日志查看。约束：token 页面不允许查看明文 token、token 保存后只展示 token hash 后缀、后台 API 需要 Bearer token、默认管理员账号仅用于初始化 |
| D3，§7.3·用户绑定 | 字段：id、feishu_open_id（唯一）、primelayer_user_id、primelayer_user_name、status、created_at、updated_at。规则：一期由管理员后台维护绑定关系；禁用状态用户不可查询 Primelayer 数据；`feishu_open_id` 为全局唯一绑定键 |
| D3，§7.4·项目级 MCP Token 管理 | 字段：id、primelayer_user_id、project_id、project_name、mcp_token_ciphertext、token_hash_suffix、token_status、imported_by、imported_at、last_used_at。要求：token 由管理员导入或替换；token 长期有效；数据库不保存明文 token；审计日志不得输出完整 token；token 调用失败时记录失败原因；同一 `primelayer_user_id + project_id` 只保留一条有效配置 |
| D3，§7.5·群聊项目绑定 | 字段：id、feishu_chat_id（唯一）、project_id、project_name、status、created_by、created_at、updated_at。规则：一个飞书群一期只绑定一个 Primelayer 项目；群聊查询默认使用群绑定项目；未绑定项目的群聊需提示管理员先完成绑定；群聊不使用群共享 token，只使用提问者个人 token |
| D3，§8.1·总体架构 | 飞书用户/群 → Feishu Adapter → Agent Task Service → RabbitMQ → Agent Worker →（Identity/User Binding、Token Resolver、DeepSeek Client、MCP Tool Registry、Primelayer MCP Adapter、Audit Service）→ Feishu Async Reply。Admin Web → Admin API →（User Binding、Project MCP Token、Chat Project Binding、Agent Task、Audit Log） |
| D3，§8.2·异步任务链路 | 飞书事件入口职责：验证并解析飞书事件、处理 URL challenge、提取 message_id/open_id/chat_id/chat_type/文本内容、基于 message_id 幂等、写入 agent_task、投递 RabbitMQ、快速返回避免飞书回调超时。Agent Worker 职责：任务状态 PENDING→RUNNING、发送"正在分析"、调用 DeepSeek 生成结构化计划、解析用户/项目/token、校验并执行 MCP 工具调用、调用 DeepSeek 汇总结果、发送飞书最终回复、写入审计日志、任务状态更新为 SUCCEEDED 或 FAILED |
| D3，§8.3·DeepSeek 调用契约 | DeepSeek 只负责理解和生成计划，不直接访问数据库、MCP 或飞书。结构化计划字段：intent、projectScope（single_project/current_chat_project/all_accessible_projects/unknown）、projectHints、toolCalls（含 toolName 和 arguments）、needClarification、clarificationQuestion、answerStyle。约束：DeepSeek 输出只作为计划建议；Gateway 必须校验工具名/参数/用户权限/项目 token；DeepSeek 不能接收 MCP token 明文；DeepSeek 不能直接决定越权项目查询 |
| D3，§8.4·Token Resolver 规则 | 私聊：问题指定项目用该项目 token；问题未指定项目但短期上下文存在项目可用上下文项目；跨项目查询遍历该用户所有有效项目 token；无法判断项目且非跨项目问题则提示用户指定项目。群聊：必须先绑定项目；使用提问者在该项目下的 MCP token；不使用群内其他成员 token；不使用群共享 token。跨项目：单次查询最大项目数由系统配置控制；单项目失败不影响整体结果；回复中必须说明失败项目和未覆盖项目 |
| D3，§8.5·MCP Tool Registry | 一期采用静态工具注册。工具定义含 tool_name、description、input_schema、permission_tag、enabled、timeout_ms。规则：只有注册且启用的工具可被调用；DeepSeek 输出不存在工具名时拒绝执行；DeepSeek 输出非法参数时拒绝执行；工具调用必须绑定 project_id 和 primelayer_user_id |
| D3，§8.6·Primelayer MCP Adapter | 使用标准 MCP Streamable HTTP 调用 Primelayer MCP Server。职责：封装 `tools/call`、注入项目级 MCP token、处理 MCP 超时/失败/空结果、标准化 MCP 返回结构、屏蔽底层 MCP API 差异、记录每次工具调用日志 |
| D3，§9·数据模型 | 6 组表：9.1 `admin_user`（id/username/password_hash/status/created_at/updated_at）；9.2 `user_binding`（id/feishu_open_id 唯一/primelayer_user_id/primelayer_user_name/status/created_at/updated_at）；9.3 `project_mcp_token`（id/primelayer_user_id/project_id/project_name/mcp_token_ciphertext/token_hash_suffix/token_status/imported_by/imported_at/last_used_at，唯一键 primelayer_user_id+project_id）；9.4 `feishu_chat_project_binding`（id/feishu_chat_id 唯一/project_id/project_name/status/created_by/created_at/updated_at）；9.5 `agent_task`（id/request_id 唯一/feishu_message_id 唯一/feishu_open_id/feishu_chat_id/chat_type/message_text/status/error_message/created_at/started_at/finished_at）；9.6 审计日志拆分为 `agent_audit_log`（请求主日志）、`agent_tool_call_log`（MCP 工具调用明细）、`agent_model_call_log`（模型调用明细） |
| D3，§9.6·审计日志要求 | 不记录完整明文 token；可记录 token ID 或 token hash 后缀；跨项目查询必须记录每个项目的调用状态；飞书异步回复能追踪到原始问题和最终回答 |
| D3，§10·接口草案 | 10.1 `POST /api/feishu/events`（飞书事件入口，接收消息事件、处理 challenge、创建异步任务）；10.2 `POST /api/admin/login`（管理员登录，返回 token 和 28800 秒过期）；10.3 `GET/POST /api/admin/user-bindings`（用户绑定管理）；10.4 `GET/POST /api/admin/project-tokens`（项目 MCP Token 管理）；10.5 `GET/POST /api/admin/chat-project-bindings`（飞书群项目绑定）；10.6 `GET /api/admin/audit-logs` + `GET /api/admin/agent-tasks`（审计与任务查看） |
| D3，§11·回复格式 | 建议格式：结论 / 关键数据 / 数据范围（项目/时间/未覆盖）/ 来源（Primelayer 链接）。群聊回答需要更短避免刷屏。复杂分析可提供简版结论、详情链接、"继续追问"提示 |
| D3，§12·异常处理 | 12.1 用户未绑定（提示联系管理员完成绑定）；12.2 群未绑定项目（提示联系管理员完成群项目绑定）；12.3 用户没有项目 token（提示联系管理员确认项目 token）；12.4 无法判断项目（提示补充项目名称）；12.5 MCP 调用失败（提示已记录错误，稍后重试或联系管理员）；12.6 DeepSeek 调用失败（提示分析服务暂时不可用，稍后重试） |
| D3，§13.1·安全需求 | MCP token 必须加密存储；DeepSeek 不能直接访问 MCP；DeepSeek prompt 中不得注入完整 token；所有工具调用必须经过 Agent Gateway 校验；群聊中使用提问者身份查询不使用群身份查询；A 用户不能使用 B 用户 token；后台默认密码必须在生产部署前修改 |
| D3，§13.2·稳定性需求 | DeepSeek 调用需设置超时；MCP 调用需设置超时；飞书发送失败需重试；RabbitMQ 消费失败进入重试或死信队列；跨项目查询中单个项目失败不应导致整体失败；结果中需说明失败或未覆盖项目 |
| D3，§13.3·可观测性需求 | 记录请求总耗时、DeepSeek 调用耗时、MCP 调用耗时、飞书发送状态；支持按用户/项目/工具查询日志；后台展示任务状态和最近审计日志 |
| D3，§13.4·成本控制需求 | 一期上线前至少加入：单用户并发限制、单群并发限制、单次查询最大 MCP 调用项目数、DeepSeek 超时时间、MCP 超时时间、最大输入数据量限制 |
| D3，§14·验收标准 | 14.1 功能验收（管理员能登录/维护绑定/导入 token 且不回显明文/绑定群项目；用户私聊查指定项目能选中正确 token；私聊查所有项目能遍历汇总；群聊绑定后 @ 机器人能查询；无 token 时返回无权限提示；MCP 失败返回清晰降级说明）。14.2 权限验收（A 用户不能用 B 用户 token；同一用户不能查未导入 token 的项目；群聊不因其他成员有权限而扩大提问者权限；工具调用经 schema 校验；非法工具或参数不执行）。14.3 审计验收（每次请求记录飞书用户/Primelayer 用户/项目/工具/参数/耗时/状态；审计日志不明文展示完整 token；跨项目查询能追踪每个项目调用结果；飞书异步回复能追踪原始问题和最终回答；后台能查看最近任务状态和审计日志） |
| D3，§15·分阶段计划 | Phase 1 Agent Gateway MVP（Spring Boot 骨架、Vue 3 后台、MySQL Flyway、飞书事件入口、RabbitMQ 异步任务、DeepSeek Client、Primelayer MCP Adapter、Identity/User Binding、Project Token Service、Token Resolver、静态 MCP Tool Registry、私聊和群聊查询、异步回复、审计日志）。Phase 2 平台化增强（Token 管理增强、MCP 工具配置后台、Agent Prompt 模板管理、回答质量反馈、项目级使用统计、DeepSeek 调用统计、工具调用回放与调试、加密主密钥迁移到环境变量或 KMS）。Phase 3 高级 Agent 能力（多步工具调用、长期任务代理、主动项目风险监控、自动日报周报、项目健康度 Agent、跨项目趋势分析 Agent、多入口接入） |
| D3，§16·当前不做事项 | 第一阶段不做：完整 Agent Studio、可视化 Agent 编排器、多租户、飞书内写回 Primelayer、任务完成/延期/转派等写操作、长期记忆、自动学习用户偏好、复杂额度和计费系统、群共享 token |
| D3，§17·关键风险 | 17.1 MCP token 安全风险（token 量多、泄露致数据暴露、MVP 密文和密钥同库有安全弱点；缓解：AES-GCM 加密、日志脱敏、后台不回显明文、严格限制管理员权限、审计 token 使用记录、生产前迁移主密钥）。17.2 群聊数据越权风险（群聊中可能出现不同权限用户；缓解：始终使用提问者身份和 token、不使用群共享 token、无 token 返回无权限提示）。17.3 DeepSeek 幻觉风险（模型可能生成不存在的数据或错误结论；缓解：回答基于 MCP 返回数据、回答包含数据范围和来源、对无数据场景明确说明、工具调用计划由 Gateway 强校验）。17.4 跨项目查询性能风险（用户项目多时 MCP 调用量大响应慢；缓解：RabbitMQ 异步执行、设置最大项目数、单项目失败不影响整体、后续增加缓存和统计预聚合） |
| D3，§18·默认假设 | DeepSeek 是底层大模型；Java/Spring Boot 是第一期后端技术栈；Vue 3 + Ant Design Vue 是一期后台技术栈；第一阶段服务单组织不做多租户；MCP token 由管理员导入；MCP token 长期有效；MCP token 粒度是 Primelayer 用户 + 项目；私聊跨项目查询默认遍历用户已绑定 token 的项目；群聊查询默认使用群绑定项目；第一阶段不在飞书内写回 Primelayer；第一阶段不做完整 Agent Studio，只做可治理的 Agent Gateway |

---

## 3. 冲突记录

> 不同资料对同一事实描述矛盾时，**并列保留两个版本**，不做裁决。

| 编号 | 冲突主题 | 版本 A | 出处 A | 版本 B | 出处 B | 差异说明 |
| --- | --- | --- | --- | --- | --- | --- |
| X1 | 智能层架构设计：DeepSeek 直连 Java 后端 vs 独立 Python agent-service | DeepSeek 直接嵌入 Java 后端，Agent Worker 调用 DeepSeek Client 生成结构化计划，无独立 Python 智能层 | D3，§8.1/§8.2/§8.3 | 实际实现为独立的 Python agent-service（FastAPI + LangGraph），5 步状态机（classify_skill → select_project_scope → plan_tool_calls → summarize_answer → quality_check），Java 后端通过 AgentServiceClient 调用此服务 | D2，§二.2/§三 | PRD 原始设计将 DeepSeek 集成在 Java 后端内，实际实现引入了独立的 Python 智能层 agent-service，架构已演进但 PRD 未更新。D1 README 描述 agent-service 为"optional"，介于两者之间 |
| X2 | DeepSeek 模型名 | `.env.example` 配置为 `deepseek-v4-pro` | D2，§五.5（引用 .env.example） | `application.yml` 默认 `deepseek-chat` | D2，§五.5（引用 application.yml） | 项目内部两个配置文件模型名不统一，且 DeepSeek 官方无 v4-pro 型号，疑似笔误。D3 PRD 仅写"DeepSeek"未指定具体模型名 |
| X3 | backend Spring Boot 版本号 | Spring Boot 3.3.7 | D2，§二 | Spring Boot（无版本号） | D3，§5 | D2 分析报告给出了具体版本号 3.3.7，D3 PRD 仅写"Spring Boot"未指定版本。D1 README 也仅写"Spring Boot"。不算矛盾，仅详细程度差异 |
| X4 | pom.xml 编译配置 | `maven-compiler-plugin` 配置 source=5, target=5（Java 5） | D2，§五.1 | `java.version` 配置为 17 | D2，§五.1（同一文件内矛盾） | 同一项目 pom.xml 中编译插件目标版本与 java.version 严重矛盾（Java 5 vs Java 17），虽可能被 Spring Boot parent 覆盖，但属明显疏漏 |
| X5 | Agent 智能性定位 | 技能分类与工具选择均为关键词匹配的确定性逻辑，模型摘要可选，离「通用 Agent 平台」目标尚远 | D2，§五.7 | DeepSeek 负责理解用户问题并生成结构化工具调用计划，Agent Gateway 根据计划调用 MCP | D3，§8.3 | D2 分析报告认为当前实现智能性有限（关键词匹配），而 D3 PRD 描述 DeepSeek 承担意图理解和计划生成职责。实际实现中 agent-service 使用 LangGraph 状态机替代了 PRD 中 DeepSeek 直连的角色 |

---

## 4. 硬指标清单

| 章节 | 硬指标 | 状态 |
| --- | --- | --- |
| §1 | 每份资料有解析状态，失败/跳过注明原因 | ✅ |
| §2 | 每份文档按章节逐条摘要，每条标注了 `D编号，§章节` | ✅ |
| §3 | 冲突信息并列保留，不做裁决 | ✅ |

---

## 附录 A：生成流程

### 流程总览

| 步骤 | 动作 | 落入章节 |
| --- | --- | --- |
| Step0 | 读取模板 + 全部原始资料（README.md / 项目分析报告.md / PRD v0.2） | — |
| Step1 | 盘点资料清单，标注解析状态（3 份均已解析） | §1 |
| Step2 | 逐份打开资料，按自身章节结构逐条摘要 | §2 |
| Step3 | 交叉比对不同资料，发现并记录 5 项冲突 | §3 |
| Step4 | 逐项核验硬指标 | §4 |

```mermaid
flowchart LR
    S0[读取模板与资料] --> S1[盘点资料清单]
    S1 --> S2[逐份精读逐章节摘要]
    S2 --> S3[交叉比对记录冲突]
    S3 --> S4[硬指标自检]
```

### 整理原则

1. **逐份精读，不跨文档归并**：摘要按文档自身章节结构组织，不做跨文档的主题重组（那是下游的事）
2. **出处即章节号**：每条摘要标注 `D编号，§章节`，直接映射回原文位置
3. **冲突保留**：矛盾信息并列保留两个版本，不擅自裁决
4. **事实驱动**：以原始资料中的事实为准，不添加主观推断

---

## 附录 B：解析 Skill

本次三份原始资料均为 Markdown 格式，使用 `Read` 工具直接读取全文。若后续有其他格式资料接入，可按以下 Skill 对应处理：

- `docx`：Word 类产品/业务文档
- `pdf`：PDF 类规范、手册、报告
- `pptx`：PPT 类方案/汇报
- `xlsx`：Excel 类数据清单、指标表
