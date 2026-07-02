"""Tests for FormDiscoveryPlanner (Task P6)."""

from app.models import ToolCall, DomainIntent, TimeRange, ProjectRef, ToolDefinition
from app.domain_config import load_domain_config
from app.form_planner import plan_form_discovery, rank_candidate_forms, plan_form_data_queries


def _config():
    return load_domain_config()


def _quality_intent():
    return DomainIntent(
        domains=["quality"],
        projectHints=[],
        timeRange=TimeRange(
            label="近30天",
            start="2026-06-02 00:00:00",
            end="2026-07-02 23:59:59",
            timezone="Asia/Shanghai",
            source="default_domain"
        ),
        depth="standard",
        confidence=0.8
    )


def _mock_available_tools():
    return [
        ToolDefinition(name="match_form_resource", description="匹配表单资源", inputSchema={}),
        ToolDefinition(name="query_form_data_list", description="查询表单数据列表", inputSchema={}),
        ToolDefinition(name="batch_get_form_value_detail", description="批量获取表单详情", inputSchema={}),
    ]


def test_quality_discovery_generates_match_form_calls():
    """质量域初始规划生成match_form_resource调用"""
    result = plan_form_discovery(
        intent=_quality_intent(),
        project_ids=["roche"],
        available_tools=_mock_available_tools(),
        config=_config()
    )
    # Should generate match_form_resource calls for core forms
    match_calls = [tc for tc in result if tc.toolName == "match_form_resource"]
    assert len(match_calls) > 0
    # Should include quality core form keywords in arguments
    for tc in match_calls:
        assert tc.purpose is not None
        assert tc.reason is not None


def test_quality_discovery_has_purpose():
    """match_form_resource调用包含purpose字段"""
    result = plan_form_discovery(
        intent=_quality_intent(),
        project_ids=["roche"],
        available_tools=_mock_available_tools(),
        config=_config()
    )
    for tc in result:
        assert tc.purpose is not None
        assert len(tc.projectIds) > 0


def test_rank_core_forms_first():
    """核心表单排在最前"""
    candidates = [
        {"formId": "f1", "formName": "质量缺陷清单", "matchScore": 0.5},
        {"formId": "f2", "formName": "安全检查表", "matchScore": 0.7},
        {"formId": "f3", "formName": "重点质量关注项", "matchScore": 0.4},
        {"formId": "f4", "formName": "质量巡检记录", "matchScore": 0.6},
    ]
    ranked = rank_candidate_forms(candidates, "quality", _config())
    # Core forms should be first
    core_names = [r["formName"] for r in ranked[:2]]
    assert "质量缺陷清单" in core_names or "重点质量关注项" in core_names


def test_exclude_forms_deprioritized():
    """排除表单降权"""
    candidates = [
        {"formId": "f1", "formName": "安全隐患台账", "matchScore": 0.9},
        {"formId": "f2", "formName": "质量缺陷清单", "matchScore": 0.3},
    ]
    ranked = rank_candidate_forms(candidates, "quality", _config())
    # Quality core form should rank higher than safety form for quality domain
    # even with lower matchScore, because safety is in excludeForms
    assert ranked[0]["formName"] == "质量缺陷清单"


def test_low_confidence_returns_clarification():
    """低置信度候选表单返回澄清"""
    candidates = [
        {"formId": "f1", "formName": "不知名表单", "matchScore": 0.1},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent(),
        project_ids=["roche"],
        available_tools=_mock_available_tools(),
        config=_config()
    )
    # Low confidence should trigger clarification, not query generation
    assert result.needClarification or len(result.toolCalls) == 0


def test_high_confidence_generates_queries():
    """高置信度候选表单生成查询调用"""
    candidates = [
        {"formId": "form_quality_defects", "formName": "质量缺陷清单", "matchScore": 0.95},
        {"formId": "form_quality_focus", "formName": "重点质量关注项", "matchScore": 0.85},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent(),
        project_ids=["roche"],
        available_tools=_mock_available_tools(),
        config=_config()
    )
    # Should generate query_form_data_list calls
    query_calls = [tc for tc in result.toolCalls if tc.toolName == "query_form_data_list"]
    assert len(query_calls) >= 1
    # Verify time filter in arguments
    for tc in query_calls:
        assert "filter" in tc.arguments or "createTime" in str(tc.arguments)
