# Lark Connect Agent Gateway

DeepSeek + Primelayer MCP + 飞书 Agent Gateway。

业务问答只有两条运行路径：

1. `MCP → DeepSeek 分析`：DeepSeek 通过原生 Tool Calling 选择实时 MCP 工具，Java 校验权限与参数、执行分页查询，再把结果交回 DeepSeek 分析。
2. `直接 DeepSeek`：问题不需要 MCP 数据时由 DeepSeek 直接回答，并明确实时外部数据限制。

Java 后端负责飞书事件、项目权限、MCP Token、工具执行、最近 5 轮上下文、审计和 Chain Trace。表单查询采用“DeepSeek 识别业务范围 → Java 自动发现并分批采集全部相关表单 → 确认分页和异步任务终态 → 按表全量脱敏分块分析 → 分层汇总”；单批最多调度 8 张表单，但相关表单总数不截断。单张表采集或分块失败时继续分析已验证的数据，只有缺失可能影响管理判断时才在回答末尾说明结论限制，完整技术信息仍保留在 Trace 与审计中。非表单工具规划仍受全程逻辑调用安全上限约束，MCP 和单表分析最大并发均为 4。

## 飞书用户与 MCP Token

- MCP Token 只绑定飞书用户 `open_id`，同一用户可以绑定多个项目 Token；群 `chat_id` 和旧 Primelayer 用户映射不再参与授权。
- 只有 `ACTIVE`、验证状态为 `VERIFIED/MANUAL` 且已从 MCP 账号信息解析出用户 ID 的 Token 才能查询。旧 Token 会在应用启动后后台补验，补验完成前不会进入查询范围。
- 单个可用 Token 自动作为默认。多个 Token 时，DeepSeek 根据当前问题及同一发言人最近 5 轮识别项目；无法唯一确定时要求确认，没有项目线索时按项目名称查询前 20 个并披露截断。
- 表单范围不足时先执行 `match_form_resource`，无结果再执行 `list_form_resource`，两种发现都无法定位资源后才要求用户补充表单范围。

## 日期约束与提示词治理

- 日期问法由 Java 解析为 `Asia/Shanghai` 时区的闭区间，并写入 `query_form_data_list.filter`。申请/创建类问法默认使用 `createTime`，完成类使用 `processFinishTime`，节点类使用 `approvalArrivalTime`。MCP 返回后还会执行本地越界校验。
- 管理后台的“提示词治理”页面管理规划、分块/单表分析、最终汇总和展示格式化四个阶段，支持全局模板、业务域覆盖、不可变版本、发布、回滚和加密快照回放。平台安全、权限、日期和脱敏约束仍由代码强制，不可在后台修改。
- 回放快照采用 AES-GCM 加密并支持管理员手动删除。当前按项目约定复用数据库中的主密钥：这可防止密文被直接读取，但不能防御数据库整体泄露。生产强化时应将主密钥迁移到环境变量或 KMS。

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
