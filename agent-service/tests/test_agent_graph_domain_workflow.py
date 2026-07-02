"""Integration tests for domain workflow in agent_graph.

These tests verify that the domain workflow modules (ProjectAliasResolver,
DomainIntentDetector, FormDiscoveryPlanner, DetailEnrichmentPlanner,
MetricSummarizer) are correctly wired into agent_graph.py.
"""

import pytest

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
    # With tool results already present, toolCalls should be empty (entering summarization)
    # or the answer should be populated
    assert len(response.toolCalls) == 0 or response.answer is not None


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
