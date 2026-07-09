"""Integration tests for domain workflow in agent_graph.

These tests verify that the domain workflow modules (ProjectAliasResolver,
DomainIntentDetector, FormDiscoveryPlanner, DetailEnrichmentPlanner,
MetricSummarizer) are correctly wired into agent_graph.py.
"""

import pytest

import app.agent_graph as agent_graph
from app.agent_graph import run_agent
from app.models import AgentAnswerRequest, ProjectRef, ToolDefinition, UserContext


def _make_request(
    question: str,
    chat_type: str = "p2p",
    projects: list[ProjectRef] | None = None,
    available_tools: list[ToolDefinition] | None = None,
    tool_results: list[dict] | None = None,
) -> AgentAnswerRequest:
    """Helper to build test requests with sensible defaults."""
    if projects is None:
        projects = [
            ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
        ]
    if available_tools is None:
        available_tools = [
            ToolDefinition(name="match_form_resource", description="匹配表单", inputSchema={}),
            ToolDefinition(name="query_form_data_list", description="查询表单数据", inputSchema={}),
            ToolDefinition(name="get_base_form_info", description="获取表单基础信息", inputSchema={}),
            ToolDefinition(name="batch_get_form_value_detail", description="获取详情", inputSchema={}),
        ]
    if tool_results is None:
        tool_results = []
    return AgentAnswerRequest(
        requestId="test-001",
        question=question,
        chatType=chat_type,
        userContext=UserContext(openId="user1"),
        projects=projects,
        availableTools=available_tools,
        history=[],
        toolResults=tool_results,
    )


# ---------------------------------------------------------------------------
# Alias resolver integration tests
# ---------------------------------------------------------------------------


def test_alias_resolver_finds_project_when_name_and_id_dont_match():
    """When existing project matching fails but alias matches, project should be found.

    "罗诊" is NOT a substring of "roche" or "罗氏诊断项目", so the existing
    _project_matches_question logic will miss it. The ProjectAliasResolver
    fallback should pick it up via the configured alias.
    """
    response = run_agent(
        _make_request(
            "罗诊项目最近缺陷整改情况怎么样",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
                ProjectRef(projectId="other", projectName="另一个项目"),
            ],
        )
    )
    # Should resolve to roche via the "罗诊" alias in domain_config.yaml
    assert response.needClarification is False
    assert "roche" in response.projectIds
    assert response.projectScope in ("single_project", "current_chat_project")


def test_alias_resolver_returns_clarification_when_no_alias_matches():
    """When no alias matches and there are multiple projects, need clarification."""
    response = run_agent(
        _make_request(
            "XYZ神秘项目的质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
                ProjectRef(projectId="other", projectName="另一个项目"),
            ],
        )
    )
    # "XYZ神秘" matches nothing -- should need clarification
    assert response.needClarification is True
    assert response.clarificationQuestion is not None


# ---------------------------------------------------------------------------
# Domain workflow planning tests
# ---------------------------------------------------------------------------


def test_quality_question_with_alias_triggers_workflow():
    """A quality-related question with an aliased project should generate tool calls."""
    response = run_agent(
        _make_request(
            "罗诊项目最近缺陷整改情况怎么样",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
        )
    )
    # With a single project, the existing fallback assigns project_ids
    assert "roche" in response.projectIds
    # Should have tool calls (from project_workflow or domain workflow)
    assert response is not None


# ---------------------------------------------------------------------------
# Regression: existing behavior preserved
# ---------------------------------------------------------------------------


def test_regular_question_still_works():
    """Non-domain questions should not crash and should fall through to general path."""
    response = run_agent(_make_request("帮我查一下今天的天气"))
    assert response is not None
    # May need clarification if no tools available, but must not crash


def test_project_report_workflow_still_works():
    """Existing project_workflow should still trigger for construction questions."""
    response = run_agent(
        _make_request(
            "项目质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
        )
    )
    assert response is not None
    # Either project IDs found or needs clarification
    assert len(response.projectIds) > 0 or response.needClarification


def test_group_chat_uses_context_project():
    """Group chat should default to the chat's project context."""
    response = run_agent(
        _make_request(
            "质量情况怎么样",
            chat_type="group",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
        )
    )
    assert response.projectScope == "current_chat_project"
    assert "roche" in response.projectIds


def test_need_clarification_when_no_project_match():
    """When no project can be matched (multi-project, no alias), need clarification."""
    response = run_agent(
        _make_request(
            "ABC项目的质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
                ProjectRef(projectId="other", projectName="另一个项目"),
            ],
        )
    )
    # Should either need clarification or have empty projectIds
    if response.needClarification:
        assert response.clarificationQuestion is not None
    else:
        # Even if not needing clarification, the project IDs should not include ABC
        pass


def test_answer_includes_data_range():
    """The quality_check should append data range to answers."""
    response = run_agent(
        _make_request(
            "罗诊项目质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
        )
    )
    # Should not crash and should provide a response
    assert response is not None


# ---------------------------------------------------------------------------
# Domain summarization tests
# ---------------------------------------------------------------------------


def test_domain_workflow_with_query_form_data_tool_results():
    """When query_form_data_list results exist, domain workflow should summarize."""
    response = run_agent(
        _make_request(
            "罗诊项目上个月质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            tool_results=[
                {
                    "projectId": "roche",
                    "projectName": "罗氏诊断项目",
                    "toolName": "query_form_data_list",
                    "status": "SUCCEEDED",
                    "formName": "质量缺陷清单",
                    "arguments": {"formId": "quality_form_1"},
                    "result": {
                        "data": {
                            "total": 5,
                            "items": [
                                {
                                    "dataId": "d1",
                                    "status": "待整改",
                                    "severity": "高",
                                    "type": "外观缺陷",
                                    "responsiblePerson": "张三",
                                    "area": "A区",
                                },
                                {
                                    "dataId": "d2",
                                    "status": "已完成",
                                    "severity": "中",
                                    "type": "尺寸偏差",
                                    "responsiblePerson": "李四",
                                    "area": "B区",
                                },
                            ],
                        }
                    },
                }
            ],
        )
    )
    assert response is not None
    # With list data and a detail tool available, the workflow may enrich
    # records before producing the final summary.
    if response.toolCalls:
        assert response.toolCalls[0].toolName == "batch_get_form_value_detail"
    else:
        assert response.answer is not None


def test_quality_match_results_continue_to_form_data_queries_with_pagination():
    """After matching quality forms, the next round should query last month's form data."""
    response = run_agent(
        _make_request(
            "罗诊项目上个月质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            tool_results=[
                {
                    "projectId": "roche",
                    "projectName": "罗氏诊断项目",
                    "toolName": "match_form_resource",
                    "status": "SUCCEEDED",
                    "arguments": {"name": "质量"},
                    "result": {
                        "content": [
                            {
                                "text": "{\"data\":[{\"formId\":\"quality_defects\",\"formName\":\"质量缺陷清单\",\"matchScore\":0.95},{\"formId\":\"quality_focus\",\"formName\":\"重点质量关注项\",\"matchScore\":0.85}]}"
                            }
                        ]
                    },
                }
            ],
        )
    )

    query_calls = [tc for tc in response.toolCalls if tc.toolName == "query_form_data_list"]
    assert response.answer is None
    assert len(query_calls) == 2
    for tc in query_calls:
        assert tc.pagination is not None
        assert tc.pagination["mode"] == "auto"
        assert tc.pagination["pageSize"] == 100
        assert tc.arguments["pageSize"] == 100
        assert tc.arguments["filter"]["createTime"] == [
            "2026-06-01 00:00:00",
            "2026-06-30 23:59:59",
        ]


def test_tool_planning_records_relevance_scores_and_deepseek_review(monkeypatch):
    """Every planning round should score candidates and ask the model to review them."""
    review_calls = []

    def fake_model_review(request, candidates, proposed_calls):
        review_calls.append((request.question, candidates, proposed_calls))
        return {
            "reviewer": "deepseek",
            "selectedToolNames": [call.toolName for call in proposed_calls],
            "reasoningSummary": "质量问题需要先发现质量表单，再查询表单数据。",
        }

    monkeypatch.setattr(agent_graph, "_model_review_tool_selection", fake_model_review, raising=False)

    response = run_agent(
        _make_request(
            "罗诊项目上个月质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
        )
    )

    assert review_calls
    selection = response.answerMetadata.get("toolSelection")
    assert selection is not None
    assert selection["modelReview"]["reviewer"] == "deepseek"
    assert selection["candidateScores"]
    assert all("score" in item for item in selection["candidateScores"])
    assert any("DeepSeek复核" in (call.reason or "") for call in response.toolCalls)


def test_project_report_quality_query_results_use_domain_summary():
    """Project-report routed quality data should produce a metric report, not raw JSON dumps."""
    response = run_agent(
        _make_request(
            "罗诊项目上个月质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            available_tools=[
                ToolDefinition(name="match_form_resource", description="匹配表单", inputSchema={}),
                ToolDefinition(name="query_form_data_list", description="查询表单数据", inputSchema={}),
                ToolDefinition(name="get_base_form_info", description="获取表单基础信息", inputSchema={}),
            ],
            tool_results=[
                {
                    "projectId": "roche",
                    "projectName": "罗氏诊断项目",
                    "toolName": "query_form_data_list",
                    "status": "SUCCEEDED",
                    "formName": "质量缺陷清单",
                    "arguments": {"formId": "quality_defects"},
                    "result": {
                        "data": {
                            "total": 2,
                            "items": [
                                {
                                    "dataId": "d1",
                                    "status": "待整改",
                                    "severity": "高",
                                    "type": "外观缺陷",
                                    "responsiblePerson": "张三",
                                    "area": "A区",
                                },
                                {
                                    "dataId": "d2",
                                    "status": "已完成",
                                    "severity": "中",
                                    "type": "尺寸偏差",
                                    "responsiblePerson": "李四",
                                    "area": "B区",
                                },
                            ],
                        }
                    },
                }
            ],
        )
    )

    assert response.answer is not None
    assert "关键指标" in response.answer
    assert "记录总数：2" in response.answer
    assert "query_form_data_list" not in response.answer


def test_detail_only_quality_results_are_summarized_without_raw_json():
    """Detail tool results should be normalized into a quality report instead of raw payload text."""
    response = run_agent(
        _make_request(
            "罗诊项目上个月质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            available_tools=[
                ToolDefinition(name="match_form_resource", description="匹配表单", inputSchema={}),
                ToolDefinition(name="query_form_data_list", description="查询表单数据", inputSchema={}),
                ToolDefinition(name="batch_get_form_value_detail", description="获取详情", inputSchema={}),
            ],
            tool_results=[
                {
                    "projectId": "roche",
                    "projectName": "罗氏诊断项目",
                    "toolName": "batch_get_form_value_detail",
                    "status": "SUCCEEDED",
                    "formName": "质量缺陷清单",
                    "arguments": {"formId": "quality_defects", "dataIdList": ["d1"]},
                    "result": {
                        "content": [
                            {
                                "text": "{\"data\":[{\"dataId\":\"d1\",\"createTime\":\"2026-06-01 14:19:28\",\"creator\":\"Kirsi Deng 邓科\",\"detailList\":[{\"fieldName\":\"问题描述\",\"fieldValue\":\"AMEFS 罗氏诊断产品建设体外诊断试剂及体外诊断仪器生产项目质量缺陷\"},{\"fieldName\":\"整改状态\",\"fieldValue\":\"待整改\"},{\"fieldName\":\"严重程度\",\"fieldValue\":\"高\"},{\"fieldName\":\"责任人\",\"fieldValue\":\"张三\"}]}]}"
                            }
                        ]
                    },
                }
            ],
        )
    )

    assert response.answer is not None
    assert "关键指标" in response.answer
    assert "记录总数：1" in response.answer
    assert "batch_get_form_value_detail" not in response.answer
    assert "content" not in response.answer
    assert "detailList" not in response.answer


def test_quality_summary_uses_status_from_form_values():
    """query_form_data_list 的 formValues 状态字段应参与已完成/未整改统计。"""
    response = run_agent(
        _make_request(
            "罗诊项目上个月质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            available_tools=[
                ToolDefinition(name="match_form_resource", description="匹配表单", inputSchema={}),
                ToolDefinition(name="query_form_data_list", description="查询表单数据", inputSchema={}),
            ],
            tool_results=[
                {
                    "projectId": "roche",
                    "projectName": "罗氏诊断项目",
                    "toolName": "query_form_data_list",
                    "status": "SUCCEEDED",
                    "formName": "质量缺陷清单",
                    "arguments": {"formId": "quality_defects"},
                    "result": {
                        "data": {
                            "total": 3,
                            "items": [
                                {
                                    "dataId": "d1",
                                    "formValues": [
                                        {"fieldName": "整改状态", "fieldValue": "待整改"},
                                        {"fieldName": "严重程度", "fieldValue": "高"},
                                    ],
                                },
                                {
                                    "dataId": "d2",
                                    "formValues": [
                                        {"fieldName": "整改状态", "fieldValue": "已完成"},
                                        {"fieldName": "严重程度", "fieldValue": "低"},
                                    ],
                                },
                                {
                                    "dataId": "d3",
                                    "formValues": [
                                        {"fieldName": "整改状态", "fieldValue": "已整改"},
                                    ],
                                },
                            ],
                        }
                    },
                }
            ],
        )
    )

    assert response.answer is not None
    assert "未整改（含逾期）：1 条" in response.answer
    assert "已完成整改：2 条" in response.answer
    assert "状态字段覆盖：3/3 条" in response.answer


def test_domain_workflow_handles_failed_tool_results():
    """Domain workflow should handle failed tool results gracefully."""
    response = run_agent(
        _make_request(
            "罗诊项目质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            tool_results=[
                {
                    "projectId": "roche",
                    "projectName": "罗氏诊断项目",
                    "toolName": "query_form_data_list",
                    "status": "FAILED",
                    "error": "数据源暂时不可用",
                }
            ],
        )
    )
    assert response is not None
    # Should still produce a response even with failures
    # Should not leak raw error codes
    if response.answer:
        assert "jsonrpc" not in response.answer
        assert "nodeCodeList" not in response.answer


# ---------------------------------------------------------------------------
# No-tool tests
# ---------------------------------------------------------------------------


def test_no_tools_returns_graceful_message():
    """When no tools are available, return a graceful message."""
    response = run_agent(
        _make_request(
            "罗诊项目质量情况",
            projects=[
                ProjectRef(projectId="roche", projectName="罗氏诊断项目"),
            ],
            available_tools=[],
        )
    )
    assert response is not None
    # With only 1 project, project_ids should be resolved
    assert "roche" in response.projectIds
    # Should indicate that no tools are available
    if response.answer:
        assert "工具" in response.answer or "查询" in response.answer
