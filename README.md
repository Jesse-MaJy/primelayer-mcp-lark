# Lark Connect Agent Gateway

DeepSeek + Primelayer MCP + 飞书 Agent Gateway。

业务问答只有两条运行路径：

1. `MCP → DeepSeek 分析`：DeepSeek 通过原生 Tool Calling 选择实时 MCP 工具，Java 校验权限与参数、执行分页查询，再把结果交回 DeepSeek 分析。
2. `直接 DeepSeek`：问题不需要 MCP 数据时由 DeepSeek 直接回答，并明确实时外部数据限制。

Java 后端负责飞书事件、项目权限、MCP Token、工具执行、最近 5 轮上下文、审计和 Chain Trace。MCP 查询最多 8 轮，每轮最多 8 个、全程最多 32 个逻辑工具调用，独立调用最大并发为 4。大于 32,000 字符的结果按分页边界分块分析；单次分页最多 50 页并披露截断。

## Modules

- `backend`: Java 17 / Spring Boot，统一 DeepSeek Tool Calling 与 MCP 安全执行层。
- `admin-web`: Vue 3 管理后台，支持 DeepSeek 模型切换、MCP 诊断、端到端查询和链路追踪。

DeepSeek Base URL 与 API Key 从环境变量读取；生产模型在后台的 `deepseek-v4-pro` 与 `deepseek-v4-flash` 之间切换，默认 V4-Pro。
