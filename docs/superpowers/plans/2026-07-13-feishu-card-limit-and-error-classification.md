# 飞书卡片上限与发送错误分类 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 防止 DeepSeek 生成超过飞书上限的表格组件，并将 AI 查询失败与飞书消息发送失败准确分类、持久化和展示。

**Architecture:** DeepSeek 提示词负责生成阶段约束，`AnswerPresentationParser` 在模型边界实施最多 5 个表格和 3 个图表的硬限制。`FeishuClient` 统一标准化 HTTP 与业务响应异常并返回降级信息，`AgentOrchestrator` 通过结构化处理结果把真实失败原因交给 Worker，同时保留成功 AI 回答。

**Tech Stack:** Java 17、Spring Boot 3.3.7、Spring RestClient、Jackson、JUnit 5、Mockito、Vue 3、TypeScript

## Global Constraints

- 单张飞书卡片最多包含 5 个表格组件。
- 单张回答卡片最多包含 3 个图表组件。
- 飞书卡片整体数据不得超过 30 KB；本次不实现拆卡或分页发送。
- 超限表格在 DeepSeek 阶段优先合并或转成 Markdown，服务端最终只保留前 5 个有效表格。
- AI 查询成功后，不得用 `ai_unavailable` 或通用错误回答覆盖成功回答、展示 JSON 和业务 intent。
- 当前工作区存在与目标文件重叠的用户未提交改动；只应用本计划明确列出的最小补丁，不回退或覆盖其他改动，不自动提交包含用户改动的文件。

---

### Task 1: 在生成和解析边界限制展示组件数量

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/agent/AnswerPresentationParser.java:17-120`
- Modify: `backend/src/main/java/com/larkconnect/agent/agent/UnifiedQueryService.java:221-238`
- Test: `backend/src/test/java/com/larkconnect/agent/agent/AnswerPresentationParserTest.java`
- Test: `backend/src/test/java/com/larkconnect/agent/agent/UnifiedQueryServiceTest.java`

**Interfaces:**
- Consumes: DeepSeek 展示 JSON 的 `blocks` 或旧版 `tables` 数组。
- Produces: `AnswerPresentationParser.MAX_TABLES = 5`；任意 `ParsedPresentation` 最多包含 5 个 `TABLE` 块。

- [ ] **Step 1: 写新版 blocks 超限失败测试**

在 `AnswerPresentationParserTest` 增加辅助方法和测试，构造 6 个有效表格并断言只保留前 5 个：

```java
@Test
void keepsOnlyFirstFiveValidTableBlocks() {
    String blocks = java.util.stream.IntStream.rangeClosed(1, 6)
            .mapToObj(this::tableBlock)
            .reduce((a, b) -> a + "," + b).orElse("");

    var parsed = parser.parse("{\"plainText\":\"摘要\",\"blocks\":[" + blocks + "]}", "fallback");

    assertThat(parsed.presentation().blocks())
            .filteredOn(block -> block.type() == AnswerPresentation.BlockType.TABLE)
            .hasSize(5)
            .extracting(block -> block.table().title())
            .containsExactly("表1", "表2", "表3", "表4", "表5");
}

private String tableBlock(int index) {
    return "{\"type\":\"table\",\"title\":\"表" + index
            + "\",\"columns\":[{\"key\":\"name\",\"label\":\"名称\"}],"
            + "\"rows\":[{\"name\":\"A\"}]}";
}
```

- [ ] **Step 2: 写有效表格配额与旧协议失败测试**

增加两个测试：第一个表格无列定义时不占配额，后续 5 个有效表格全部保留；旧版 `tables` 输入 6 个时也只保留 5 个。断言均以最终 `TABLE` 块数量和标题顺序为准。

- [ ] **Step 3: 运行 Parser 测试确认 RED**

Run: `cd backend && mvn -Dtest=AnswerPresentationParserTest test`

Expected: 新测试 FAIL，实际得到 6 个表格。

- [ ] **Step 4: 实现 Parser 的 5 表格硬限制**

在解析器常量区增加：

```java
static final int MAX_TABLES = 5;
```

`parseBlocks` 使用 `tableCount`，只在 `parseTable` 返回非空且成功加入列表时递增；达到 `MAX_TABLES` 后忽略后续表格。`parseTables` 的循环条件改为在 `tables.size() >= MAX_TABLES` 时停止，保证无效表格不占配额。

- [ ] **Step 5: 运行 Parser 测试确认 GREEN**

Run: `cd backend && mvn -Dtest=AnswerPresentationParserTest test`

Expected: PASS。

- [ ] **Step 6: 写展示提示词失败测试**

扩展 `UnifiedQueryServiceTest.requestsOrderedPresentationBlocksInsteadOfDetachedCollections`：

```java
assertThat(instruction)
        .contains("表格块最多 5 个")
        .contains("图表块最多 3 个")
        .contains("合并同类表格")
        .contains("改为 Markdown")
        .contains("不得通过拆分同一份数据规避组件上限");
```

- [ ] **Step 7: 运行提示词测试确认 RED**

Run: `cd backend && mvn -Dtest=UnifiedQueryServiceTest#requestsOrderedPresentationBlocksInsteadOfDetachedCollections test`

Expected: FAIL，提示词缺少数量限制。

- [ ] **Step 8: 完成提示词最小修改并确认 GREEN**

在 `presentationInstruction()` 的格式定义后加入：

```text
单张飞书卡片中 table 表格块最多 5 个，chart 图表块最多 3 个。
维度超过上限时必须合并同类表格，或将次要明细改为 Markdown；不得通过拆分同一份数据规避组件上限。
```

Run: `cd backend && mvn -Dtest=UnifiedQueryServiceTest#requestsOrderedPresentationBlocksInsteadOfDetachedCollections test`

Expected: PASS。

---

### Task 2: 标准化飞书 HTTP 错误并让内容错误进入降级路径

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/feishu/FeishuClient.java:20-220,440-477`
- Test: `backend/src/test/java/com/larkconnect/agent/feishu/FeishuClientCardFallbackTest.java`

**Interfaces:**
- Produces: `FeishuClient.DeliveryResult(boolean fallbackUsed, String warning)`。
- Produces: `FeishuClient.deliveryFailureReason(Throwable): String`，将表格超限转换为“卡片表格数量超过飞书上限（最多 5 个）”。
- Produces: HTTP 4xx/5xx 也统一抛出 `FeishuApiException`，携带飞书 `code`、`msg` 和响应体。

- [ ] **Step 1: 写 HTTP 400 标准化失败测试**

在 `FeishuClientCardFallbackTest` 使用 `HttpClientErrorException.create(...)` 构造以下响应体：

```json
{"code":230099,"msg":"Failed to create card content, ext=ErrCode: 11310; ErrMsg: card table number over limit; ErrorValue: table;"}
```

测试包级辅助方法 `translateHttpFailure` 返回的异常满足：

```java
assertThat(error.code()).isEqualTo(230099);
assertThat(error.cardContentError()).isTrue();
assertThat(FeishuClient.deliveryFailureReason(error))
        .isEqualTo("卡片表格数量超过飞书上限（最多 5 个）");
```

- [ ] **Step 2: 运行飞书降级测试确认 RED**

Run: `cd backend && mvn -Dtest=FeishuClientCardFallbackTest test`

Expected: 编译失败或断言失败，因为标准化接口尚不存在。

- [ ] **Step 3: 实现 HTTP 异常转换**

引入 `RestClientResponseException`。在 `replyCard` 和 `sendCard` 的 `.retrieve().body(...)` 周围捕获它，并调用包级方法：

```java
FeishuApiException translateHttpFailure(RestClientResponseException error, String action) {
    JsonNode root = parseSafely(error.getResponseBodyAsString());
    int code = root.path("code").asInt(error.getStatusCode().value());
    String message = root.path("msg").asText(error.getStatusText());
    return new FeishuApiException(code, message, action, error);
}
```

给 `FeishuApiException` 增加 cause 构造参数和包级 `code()`，保持原有三参数构造器兼容现有测试。

- [ ] **Step 4: 实现可读发送原因映射**

增加：

```java
public static String deliveryFailureReason(Throwable error) {
    String message = readableThrowable(error);
    if (message.toLowerCase(Locale.ROOT).contains("card table number over limit")) {
        return "卡片表格数量超过飞书上限（最多 5 个）";
    }
    return message;
}
```

不得丢失未知飞书错误码和消息。

- [ ] **Step 5: 让结构化回答发送返回降级结果**

把两个 `replyAnswerFeedbackCard` 重载及私有 `replyPresentationWithFallback` 的返回值改为：

```java
public record DeliveryResult(boolean fallbackUsed, String warning) {
    public static DeliveryResult direct() { return new DeliveryResult(false, null); }
    public static DeliveryResult fallback(String warning) { return new DeliveryResult(true, warning); }
}
```

首次发送成功返回 `direct()`；内容错误后 Markdown 发送成功返回 `fallback("飞书结构化卡片发送失败，已降级为 Markdown：" + deliveryFailureReason(error))`。

- [ ] **Step 6: 验证 HTTP 内容错误触发降级且非内容错误不重试**

扩展现有 Stub 测试，断言内容错误调用两次且返回 `fallbackUsed=true`；网络错误只调用一次并继续抛出。

Run: `cd backend && mvn -Dtest=FeishuClientCardFallbackTest test`

Expected: PASS。

---

### Task 3: 区分 AI 查询失败与飞书发送失败并保留成功结果

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java:57-109`
- Modify: `backend/src/main/java/com/larkconnect/agent/agent/AgentWorker.java:17-29`
- Test: `backend/src/test/java/com/larkconnect/agent/agent/AgentOrchestratorTest.java`
- Test: `backend/src/test/java/com/larkconnect/agent/agent/AgentWorkerTest.java`

**Interfaces:**
- Produces: `AgentOrchestrator.ProcessResult(boolean succeeded, String error)`。
- `AgentOrchestrator.process(String)` 从 `boolean` 改为 `ProcessResult`。
- `AgentWorker` 使用 `ProcessResult.error()` 写入 `agent_task.error_message`。

- [ ] **Step 1: 写 Worker 真实错误传播失败测试**

将现有 Worker 测试改为：

```java
when(orchestrator.process("r1")).thenReturn(
        AgentOrchestrator.ProcessResult.failed(
                "飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）"));

new AgentWorker(tasks, orchestrator).handle(new AgentTaskMessage("r1"));

verify(tasks).markFailed("r1", "飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）");
```

- [ ] **Step 2: 写 Orchestrator 发送失败不覆盖 AI 结果的失败测试**

让 `unified.query` 返回成功结果，让 `feishu.replyAnswerFeedbackCard` 抛出表格超限异常。断言：

```java
assertThat(outcome.succeeded()).isFalse();
assertThat(outcome.error()).isEqualTo(
        "飞书消息发送失败：卡片表格数量超过飞书上限（最多 5 个）");
verify(audit, never()).writeModel(any(), any(), eq("unified_query"), any(), any(), eq("FAILED"), any(Long.class), any());
verify(audit).writeMain(eq("r-send"), eq("u1"), eq("c1"), eq(null), eq(List.of("P1")),
        eq("分析项目"), eq("mcp_deepseek"), eq("风险分析完成"),
        eq("{\"plainText\":\"风险分析完成\"}"), any(Long.class),
        contains("飞书消息发送失败"));
```

同时验证系统尝试发送一张精简的“AI 已完成回答，但飞书消息发送失败”错误卡片。

- [ ] **Step 3: 运行编排测试确认 RED**

Run: `cd backend && mvn -Dtest=AgentWorkerTest,AgentOrchestratorTest test`

Expected: 编译失败或断言失败，因为 `ProcessResult` 和独立发送异常边界尚不存在。

- [ ] **Step 4: 引入 ProcessResult 并拆分异常边界**

在 `AgentOrchestrator` 增加：

```java
public record ProcessResult(boolean succeeded, String error) {
    public static ProcessResult success() { return new ProcessResult(true, null); }
    public static ProcessResult failed(String error) { return new ProcessResult(false, error); }
}
```

控制命令和正常路径返回 `success()`；AI 查询 catch 返回 `failed("AI 服务暂不可用")`。AI 成功后的飞书调用放入单独 try/catch：

- `DeliveryResult.direct()`：正常成功。
- `DeliveryResult.fallback(...)`：任务成功，并用原回答再次写主审计、记录 warning。
- 最终发送异常：用原始 `QueryResult` 再次写主审计并记录“飞书消息发送失败：...”错误，不写失败模型记录、不写 `ai_unavailable`、不覆盖原回答。

补偿错误卡片发送使用独立 try/catch；失败时保留最初发送错误。

- [ ] **Step 5: 修改 Worker 使用结构化结果**

```java
AgentOrchestrator.ProcessResult result = orchestrator.process(message.requestId());
if (result.succeeded()) taskService.markSucceeded(message.requestId());
else taskService.markFailed(message.requestId(), result.error());
```

仍然不因已处理的业务失败抛异常触发 RabbitMQ 重试。

- [ ] **Step 6: 运行编排测试确认 GREEN**

Run: `cd backend && mvn -Dtest=AgentWorkerTest,AgentOrchestratorTest test`

Expected: PASS。

---

### Task 4: 管理后台优先展示真实审计错误并完成回归验证

**Files:**
- Modify: `admin-web/src/views/FeishuMessagesView.vue:105-108`
- Verify: `admin-web/src/api/admin.contract-test.ts`
- Verify: all modified backend tests

**Interfaces:**
- Consumes: `listFeishuMessages` 已返回的 `audit_error` 与 `task_error`。
- Produces: 详情页错误展示优先级 `audit_error -> task_error -> '-'`。

- [ ] **Step 1: 修改错误字段优先级**

将详情模板改为：

```vue
<pre>{{ record.audit_error || record.task_error || '-' }}</pre>
```

- [ ] **Step 2: 运行前端类型检查与构建**

Run: `cd admin-web && npm run build`

Expected: `vue-tsc --noEmit` 和 `vite build` 均成功。

- [ ] **Step 3: 运行后端目标测试集合**

Run:

```bash
cd backend
mvn -Dtest=AnswerPresentationParserTest,UnifiedQueryServiceTest,FeishuClientCardFallbackTest,AgentOrchestratorTest,AgentWorkerTest test
```

Expected: 所有目标测试 PASS，0 failures，0 errors。

- [ ] **Step 4: 运行后端完整测试**

Run: `cd backend && mvn test`

Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 5: 检查最终差异范围**

Run: `git diff --check`，并逐个检查本计划列出的文件，确认没有回退当前工作区已有的链路追踪、日期处理、卡片展示等改动。

Expected: 无空白错误；最终差异只新增本设计要求的行为。由于目标文件已有用户未提交改动，本步骤不自动执行 `git add` 或 `git commit`。
