# 飞书卡片上限与发送错误分类设计

## 目标

解决 DeepSeek 已成功生成回答，但展示格式化结果包含过多表格，导致飞书拒绝卡片后被错误反馈为“AI 服务暂不可用”的问题。

系统必须同时做到：

- DeepSeek 展示格式化阶段主动遵守飞书卡片上限。
- Java 服务端不信任模型输出，对表格和图表数量实施硬限制。
- AI 查询失败与飞书消息发送失败分别记录、分别反馈。
- 飞书发送失败不得覆盖已经成功生成的 AI 回答、展示数据和业务 intent。
- 管理后台优先展示可用于排障的真实错误。

## 平台约束

- 单张飞书卡片最多包含 5 个表格组件。
- 单张回答卡片最多包含 3 个图表组件，沿用当前应用限制。
- 飞书卡片整体数据不得超过 30 KB；本次不引入回答拆卡或分页发送。
- 超出表格数量时，DeepSeek 应优先合并同类表格或改用 Markdown；服务端最终只接受前 5 个有效表格。

## 方案选择

采用“模型提示约束 + 服务端解析硬限制 + 发送错误分类”的三层方案。

仅修改提示词不能保证模型始终遵守限制；仅在卡片渲染阶段截断会让不合法的展示结构继续流入后续流程。解析阶段是模型输出进入应用领域对象的边界，适合作为数量限制的最终执行点。

## 组件设计

### DeepSeek 展示格式化

`UnifiedQueryService.presentationInstruction()` 增加明确约束：

- `table` 内容块最多 5 个。
- `chart` 内容块最多 3 个。
- 数据维度超过限制时合并同类表格，或者将次要明细改成 Markdown。
- 不得通过拆分同一份数据规避组件上限。

该层用于尽量生成合法且语义完整的展示 JSON，减少服务端丢弃超限结构的概率。

### 展示结构解析

`AnswerPresentationParser` 新增 `MAX_TABLES = 5`。

解析新版 `blocks` 和旧版 `tables` 时都只接受前 5 个有效表格。无效表格不计入配额；达到配额后的表格块不再加入 `AnswerPresentation`。现有 `MAX_CHARTS = 3` 行为保持不变。

解析器是硬兜底，不依赖 DeepSeek 是否严格执行提示词。

### 飞书异常标准化

`FeishuClient` 必须把两类失败统一转换成带飞书错误码和消息的 `FeishuApiException`：

- HTTP 2xx、响应 JSON 中 `code != 0`。
- HTTP 4xx/5xx，由 Spring `RestClient` 在读取响应体前抛出的响应异常。

HTTP 异常转换后，现有卡片内容错误降级逻辑才能识别 `card`、`content`、`table number over limit` 等错误并尝试 Markdown 卡片。

如果结构化卡片失败、Markdown 降级卡片成功，本次任务视为成功，但审计中记录展示降级原因。若两次发送均失败，则向上抛出飞书发送异常。

### 编排与状态

`AgentOrchestrator` 将 AI 查询处理和飞书发送处理分成两个异常边界：

- 查询阶段失败：保持现有“AI 服务暂不可用”语义，intent 写为 `ai_unavailable`。
- 查询成功、发送阶段失败：保留成功的 `mcp_deepseek` 或 `direct_deepseek` intent、最终回答和 `presentation_json`，错误写入 `audit_error`，并将任务结果返回为失败。

飞书发送失败的标准错误文案为：

`AI 已完成回答，但飞书消息发送失败：<可读原因>`

对于 `card table number over limit`，可读原因明确转换为：

`卡片表格数量超过飞书上限（最多 5 个）`

系统可以尝试发送一张不包含原回答的精简错误卡片；该补偿发送失败不得覆盖最初的飞书异常。

### Worker 与管理后台

`AgentWorker` 不再把 `process()` 返回的所有失败固定写成“AI 服务暂不可用”。编排结果需要携带失败类别和可读错误，供任务表保存真实原因。

管理后台详情中的错误显示优先级调整为：

1. `audit_error`
2. `task_error`
3. `-`

这样即使任务表只保存摘要，排障页面仍优先展示包含飞书错误码或可读原因的审计错误。

## 数据流

1. DeepSeek 生成业务回答。
2. DeepSeek 按最多 5 表格、3 图表生成展示 JSON。
3. Parser 再次施加 5/3 的硬限制。
4. Orchestrator 先持久化成功回答和展示结构。
5. FeishuClient 发送结构化卡片。
6. 卡片内容错误时，FeishuClient 转换异常并尝试 Markdown 降级。
7. 发送成功则任务成功；两次均失败则任务失败，但 AI 成功结果保留，错误分类为飞书消息发送失败。

## 测试策略

- `AnswerPresentationParserTest`
  - 6 个有效表格只保留前 5 个。
  - 无效表格不占用 5 个有效表格配额。
  - 旧版 `tables` 同样限制为 5 个。
- `UnifiedQueryServiceTest`
  - 展示格式化提示包含表格最多 5 个、图表最多 3 个及合并/Markdown 要求。
- `FeishuClientCardFallbackTest`
  - HTTP 400 响应中的飞书错误被转换并触发 Markdown 降级。
  - 降级成功时不向上抛异常。
  - 两次发送失败时保留最初的结构化卡片错误。
- `AgentOrchestratorTest`
  - AI 查询成功、飞书发送失败时不写 `ai_unavailable`，不覆盖成功回答。
  - 返回真实的飞书发送失败类别与可读错误。
- `AgentWorkerTest`
  - 任务失败时保存编排器返回的真实错误，而不是固定 AI 错误。
- 前端契约或组件测试
  - 详情优先展示 `audit_error`。

## 不在本次范围

- 将长回答自动拆成多张飞书消息。
- 为超过 30 KB 的卡片实现通用体积预算器。
- 改变 DeepSeek 业务分析内容或 MCP 查询策略。
- 修改飞书表格单表行数、列数限制。

## 验收标准

- DeepSeek 展示提示明确限制最多 5 个表格和 3 个图表。
- 任意模型输出经过解析后最多包含 5 个表格和 3 个图表。
- HTTP 400 的飞书卡片错误可进入现有 Markdown 降级路径。
- AI 成功但飞书最终发送失败时，任务错误明确写为飞书消息发送失败。
- 成功 AI 回答、业务 intent 和展示 JSON 不会被 `ai_unavailable` 覆盖。
- 管理后台优先显示真实审计错误。
- 后端与相关前端测试全部通过。
