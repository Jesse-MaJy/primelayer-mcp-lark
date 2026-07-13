# Agent 双路径查询能力设计

> 当前生效设计。2026-07-13 起替代 FastGPT、Python agent-service 和旧 DeepSeek JSON Planner 方案。

业务消息统一进入 Java `UnifiedQueryService` 和一个 DeepSeek Tool Calling 会话。模型直接返回正文时走 `direct_deepseek`；返回工具调用时，Java 对实时 MCP schema、只读属性、项目权限和参数做校验，执行后把结果作为 tool message 回传，最终走 `mcp_deepseek`。

查询最多 8 个 MCP 轮次，每轮最多 8 个、全程最多 32 个逻辑调用，独立逻辑调用最大并发 4。分页与大结果分块不计逻辑调用；分页最多 50 页，结果超过 32,000 字符时按页分块交给 DeepSeek 提炼，任何上限或失败均在最终答案披露。

上下文读取现有任务和审计表中最近 5 组成功业务问答：私聊按 open_id 与 chat_id 隔离，群聊按 chat_id 共享。配置检查与绑定提示仍是确定性控制流程，不进入业务问答历史。
