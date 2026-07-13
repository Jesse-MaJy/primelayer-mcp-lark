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

## 飞书回答反馈

正常的 DeepSeek 与项目分析回答会附带“有帮助 / 有问题”按钮。用户可选择数据不准确、答非所问、内容不完整，或通过必填输入框提交最多 500 字的其他说明。每位用户对每条回答保留一条最新评价，管理员可在“飞书消息记录”中查看汇总与明细。

启用交互前，需要在飞书开发者后台完成以下配置并发布新的应用版本：

1. 在“事件与回调”中只订阅新版 `card.action.trigger`（卡片回传交互），并移除旧版 `card.action.trigger_v1`。
2. 将请求地址配置为公开可访问的 `https://<domain>/api/feishu/card-events`。
3. 将同一 Verification Token 写入服务端 `FEISHU_VERIFICATION_TOKEN`。
4. 不启用 Encrypt Key；当前卡片回调端点会明确拒绝尚未支持解密的事件。

飞书要求卡片回调在 3 秒内响应。卡片交互有效期为 30 天，可更新有效期为 14 天；超过平台期限后由飞书客户端提示用户。
