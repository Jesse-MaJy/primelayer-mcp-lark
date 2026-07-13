# 项目问答智能体增强代码级实施计划

> 本文档只描述代码级实施步骤，不包含实际代码改动。实现时建议按 TDD 执行，每个任务先写失败测试，再写最小实现。

## 1. 实施目标

将 Python `agent-service` 增强为领域驱动的项目问答智能层，支持质量、安全、进度、风险问答，并通过 Java 后端安全执行 MCP 工具和分页查询。

## 2. 总体任务顺序

1. Python 数据模型扩展。
2. Python 轻配置加载。
3. 时间解析器。
4. 项目别名解析器。
5. 业务域识别器。
6. 表单发现与排序规划器。
7. 表单数据查询与分页意图。
8. 详情增强规划器。
9. 管理报告式摘要器。
10. Java DTO 与分页执行适配。
11. 链路追踪字段增强。
12. 离线评估样例和评分脚本。
13. 回归测试与验收。

## 3. Python 实施计划

### Task P1: 扩展 ToolCall 模型

文件：

- `agent-service/app/models.py`
- `agent-service/tests/test_domain_workflow.py`

新增字段：

- `pagination: dict[str, Any] | None`
- `purpose: str | None`

测试：

- 构造 `ToolCall` 时可包含 `pagination` 和 `purpose`。
- 老请求不包含新增字段时仍可正常解析。

验收：

- 现有 `agent-service/tests/test_skills.py` 不需要修改即可通过。

### Task P2: 新增领域配置

文件：

- `agent-service/app/domain_config.py`
- `agent-service/app/domain_config.yaml`
- `agent-service/tests/test_domain_config.py`

配置内容：

- 默认分页：pageSize=100、maxPages=50、maxItems=5000。
- 项目别名：至少包含 roche / 罗诊。
- 四个业务域：quality、safety、progress、risk。
- 每个业务域包含关键词、核心表单、补充表单、排除词。

测试：

- 能加载默认配置。
- roche 配置包含“罗诊”和“Roche”。
- quality 配置包含“质量缺陷清单”和“重点质量关注项”。

### Task P3: 实现 TimeRangeResolver

文件：

- `agent-service/app/time_range.py`
- `agent-service/tests/test_time_range.py`

接口：

```python
resolve_time_range(question: str, today: date | None = None, default_domain: str | None = None) -> TimeRange
```

测试用例：

- 2026-07-02 + “上个月” => 2026-06-01 至 2026-06-30。
- 2026-01-15 + “上个月” => 2025-12-01 至 2025-12-31。
- “本周”“上周”“近7天”“本季度”“上季度”。
- 未指定时间 + quality => 近 30 天。
- 未指定时间 + progress => 本周。

### Task P4: 实现 ProjectAliasResolver

文件：

- `agent-service/app/project_alias.py`
- `agent-service/tests/test_project_alias.py`

接口：

```python
resolve_project_ids(question: str, projects: list[ProjectRef], config: DomainConfig) -> ProjectMatch
```

返回：

- `projectIds`
- `confidence`
- `needClarification`
- `clarificationQuestion`

测试用例：

- “罗诊项目”命中 `roche`。
- “Roche”命中 `roche`。
- 直接项目 ID 命中。
- 多项目别名冲突时需要澄清。
- 群聊上下文单项目时默认使用该项目。

### Task P5: 实现 DomainIntentDetector

文件：

- `agent-service/app/domain_intent.py`
- `agent-service/tests/test_domain_intent.py`

接口：

```python
detect_domain_intent(question: str, config: DomainConfig) -> DomainIntent
```

测试用例：

- “质量缺陷情况” => quality。
- “安全隐患” => safety。
- “当前进度” => progress。
- “逾期风险和阻塞项” => risk。
- “进度风险” => progress + risk。
- 未命中 => general。

### Task P6: 实现 FormDiscoveryPlanner

文件：

- `agent-service/app/form_planner.py`
- `agent-service/tests/test_form_planner.py`

职责：

- 初始阶段生成 `match_form_resource` 调用。
- 工具结果返回后提取候选表单。
- 根据核心表单、补充表单、排除词排序。
- 生成 `query_form_data_list` 调用。

测试用例：

- quality 初始规划优先生成“质量缺陷清单”“重点质量关注项”。
- 候选表单包含无关安全表单时降权或排除。
- 表单低置信度时返回澄清。

### Task P7: 表单数据查询和分页意图

文件：

- `agent-service/app/form_planner.py`
- `agent-service/tests/test_form_data_planning.py`

`query_form_data_list` 参数：

- `formId`
- `page=1`
- `pageSize=100`
- `filter.createTime=[start, end]`

`ToolCall.pagination`：

```json
{
  "mode": "auto",
  "pageSize": 100,
  "maxPages": 50,
  "maxItems": 5000
}
```

测试用例：

- “罗诊项目上个月的质量情况”生成 2 个质量表单数据查询。
- 每个查询包含 2026-06-01 到 2026-06-30。
- 每个查询包含分页意图。

### Task P8: 详情增强规划

文件：

- `agent-service/app/detail_planner.py`
- `agent-service/tests/test_detail_planner.py`

职责：

- 从 `query_form_data_list` 结果提取 dataId。
- 优先选择未闭环、高严重度、逾期、阻塞记录。
- 生成 `batch_get_form_value_detail`。

测试用例：

- 从列表结果提取最多 20 条详情 ID。
- 已查询过的详情不重复查询。
- 无 dataId 时不生成详情调用。

### Task P9: 管理报告式摘要

文件：

- `agent-service/app/metric_summarizer.py`
- `agent-service/tests/test_metric_summarizer.py`

职责：

- 确定性统计基础指标。
- 生成模型摘要 prompt。
- 无模型可用时输出确定性兜底报告。

质量域测试：

- 统计总数、未闭环、高严重度。
- 输出包含“结论”“关键指标”“趋势与风险”“建议动作”“数据范围”。
- 部分工具失败时输出“未覆盖/失败说明”。

### Task P10: 接入 agent_graph

文件：

- `agent-service/app/agent_graph.py`
- `agent-service/app/project_workflow.py`
- `agent-service/tests/test_agent_graph_domain_workflow.py`

改造：

- 在 `_select_project_scope` 前后接入 `ProjectAliasResolver`。
- 在 `_plan_tool_calls` 中优先使用领域工作流。
- 保留原有 `project_workflow` 作为兼容路径。

回归测试：

- 现有 `test_skills.py` 全部通过。
- 新增罗诊质量场景通过。

## 4. Java 实施计划

### Task J1: 扩展 AgentServiceDtos.ToolCall

文件：

- `backend/src/main/java/com/larkconnect/agent/agent/AgentServiceDtos.java`
- `backend/src/test/java/com/larkconnect/agent/agent/AgentServiceDtosTest.java`

新增字段：

- `Map<String, Object> pagination`
- `String purpose`

测试：

- 老 JSON 不含新增字段时反序列化成功。
- 新 JSON 含 pagination/purpose 时字段正确。

### Task J2: 执行分页意图

文件：

- `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java`
- `backend/src/test/java/com/larkconnect/agent/agent/AgentOrchestratorTest.java`

逻辑：

- `pagination.mode=auto` 时调用 `mcpAdapter.callToolWithPagination`。
- 未声明 pagination 时保持 `callTool`。
- 分页结果按页写入 `toolResults`。

测试：

- total=353、pageSize=100 时产生 4 页结果。
- 第 2 页失败时仍返回其他页。
- `toolResults` 包含 page/pageSize/totalCount/status。

### Task J3: McpAdapter 分页增强

文件：

- `backend/src/main/java/com/larkconnect/agent/mcp/McpAdapter.java`
- `backend/src/test/java/com/larkconnect/agent/mcp/McpAdapterTest.java`

支持：

- page/pageSize。
- offset/limit。
- total、totalCount、totalPages。
- items、data、records、list。

测试：

- totalCount 形态。
- total 形态。
- 无分页字段时单页。
- 达到 maxPages 时停止。

### Task J4: ChainTrace 字段增强

文件：

- `backend/src/main/java/com/larkconnect/agent/audit/ChainTrace.java`
- `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java`

新增 trace metadata：

- `purpose`
- `pagination`
- `page`
- `pageSize`
- `totalCount`
- `truncated`

测试：

- 分页工具调用 trace 中包含 page 和 pageSize。
- 失败页 trace 包含 error。

## 5. 评估体系实施计划

### Task E1: 新增样例集

文件：

- `agent-service/evaluation/project_agent_cases.json`
- `agent-service/tests/test_evaluation_cases.py`

要求：

- 不少于 30 条。
- 覆盖质量、安全、进度、风险。
- 至少包含：
  - 罗诊上个月质量情况。
  - 本周安全隐患。
  - 当前进度风险。
  - 所有项目逾期风险。

测试：

- 样例数量 >= 30。
- 每条样例包含必填字段。
- 每条样例包含期望业务域、项目或范围、时间、工具序列、回答要点。

### Task E2: 新增评估脚本

文件：

- `agent-service/app/evaluation.py`
- `agent-service/tests/test_evaluation_metrics.py`

指标：

- `tool_selection_accuracy`
- `time_range_accuracy`
- `core_form_accuracy`
- `answer_scope_coverage`
- `failure_disclosure_coverage`

测试：

- 给定 mock 预测和期望，准确计算指标。
- 缺少数据范围时 `answer_scope_coverage` 下降。
- 缺少失败说明时 `failure_disclosure_coverage` 下降。

## 6. 推荐提交顺序

1. `test: add project agent intelligence planning tests`
2. `feat(agent-service): add domain config and time resolver`
3. `feat(agent-service): add project alias and domain intent detection`
4. `feat(agent-service): add form discovery and pagination planning`
5. `feat(agent-service): add metric summarizer`
6. `feat(backend): support agent-service pagination tool calls`
7. `feat(agent-service): add evaluation cases and metrics`
8. `test: add end-to-end project agent scenarios`

## 7. 验证命令

Python：

```bash
agent-service/.venv/bin/python -m pytest agent-service/tests -q
```

Java：

```bash
cd backend
mvn test
```

前端如链路页面需要调整：

```bash
cd admin-web
npm run build
```

## 8. 最终验收清单

- [ ] “罗诊项目上个月的质量情况”能生成正确项目、时间、质量表单、分页意图。
- [ ] total=353、pageSize=100 时 Java 执行 4 页。
- [ ] 多表单结果可合并汇总。
- [ ] 部分页失败不阻断最终回答。
- [ ] 最终回答包含数据范围。
- [ ] 最终回答包含失败说明。
- [ ] 评估样例不少于 30 条。
- [ ] Python 测试通过。
- [ ] Java 测试通过。
- [ ] 审计和链路追踪不包含 token。
> **已废弃（2026-07-13）**：本文描述的 Python agent-service 路线已由 `2026-07-13-agent-dual-query-design.md` 取代，仅保留历史参考。
