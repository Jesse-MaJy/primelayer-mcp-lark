# 飞书回答长度上限调整实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将飞书回答卡片的应用侧截断阈值从 3000 个 Java 字符调整为 10000 个字符。

**Architecture:** 保持现有 `FeishuClient` 卡片构建和 `safeText` 截断路径不变，只调整阈值。通过公开的 `buildAnswerFeedbackCard` 构建真实卡片并递归读取回答 Markdown 内容，验证两个边界。

**Tech Stack:** Java 17、Spring Boot 3.3.7、JUnit 5、Jackson

## Global Constraints

- 长度小于或等于 10000：完整保留。
- 长度大于 10000：保留前 10000 个字符，并追加“内容较长，已截断。”。
- 不修改飞书消息发送、卡片结构、分页或多消息拆分逻辑。

---

### Task 1: 调整回答卡片截断边界

**Files:**
- Modify: `backend/src/main/java/com/larkconnect/agent/feishu/FeishuClient.java:502-506`
- Test: `backend/src/test/java/com/larkconnect/agent/feishu/FeishuClientFeedbackCardTest.java`

**Interfaces:**
- Consumes: `FeishuClient.buildAnswerFeedbackCard(String, String, String, String, String, AnswerFeedbackView)`
- Produces: `safeText(String)` 在 10000 字符边界上的新行为；公开方法签名保持不变。

- [ ] **Step 1: 写入 10000 字符边界测试**

在 `FeishuClientFeedbackCardTest` 中增加：

```java
@Test
void keepsAnswerAtTenThousandCharacters() {
    String answer = "答".repeat(10_000);

    JsonNode card = json(client.buildAnswerFeedbackCard(
            "req-1", "问题", answer, "DeepSeek 回答", "blue",
            FeishuClient.AnswerFeedbackView.initial()
    ));

    assertEquals("**回答**\n" + answer, findContentStartingWith(card, "**回答**\n"));
}

@Test
void truncatesAnswerAfterTenThousandCharacters() {
    String answer = "答".repeat(10_001);

    JsonNode card = json(client.buildAnswerFeedbackCard(
            "req-1", "问题", answer, "DeepSeek 回答", "blue",
            FeishuClient.AnswerFeedbackView.initial()
    ));

    assertEquals(
            "**回答**\n" + "答".repeat(10_000) + "\n\n内容较长，已截断。",
            findContentStartingWith(card, "**回答**\n")
    );
}

private String findContentStartingWith(JsonNode root, String prefix) {
    if (root.isObject() && root.path("content").asText().startsWith(prefix)) {
        return root.path("content").asText();
    }
    for (JsonNode child : root) {
        String found = findContentStartingWith(child, prefix);
        if (!found.isEmpty()) return found;
    }
    return "";
}
```

- [ ] **Step 2: 运行测试确认先失败**

Run: `cd backend && mvn -Dtest=FeishuClientFeedbackCardTest test`

Expected: FAIL；10000 字符回答仍在 3000 字符处被截断。

- [ ] **Step 3: 完成最小生产代码修改**

将 `FeishuClient.safeText` 的实现改为：

```java
private String safeText(String value) {
    if (!hasText(value)) {
        return "-";
    }
    return value.length() <= 10000 ? value : value.substring(0, 10000) + "\n\n内容较长，已截断。";
}
```

- [ ] **Step 4: 运行目标测试确认通过**

Run: `cd backend && mvn -Dtest=FeishuClientFeedbackCardTest test`

Expected: PASS，`FeishuClientFeedbackCardTest` 全部测试通过。

- [ ] **Step 5: 运行后端完整测试**

Run: `cd backend && mvn test`

Expected: PASS，无测试失败或错误。
