"""Tests for form data query planning with pagination intent (Task P7)."""

from datetime import date
from app.models import ToolCall, DomainIntent, TimeRange, ProjectRef, ToolDefinition
from app.domain_config import load_domain_config
from app.domain_intent import detect_domain_intent
from app.time_range import resolve_time_range
from app.form_planner import plan_form_data_queries


def _config():
    return load_domain_config()


def _available_tools():
    return [
        ToolDefinition(name="match_form_resource", description="匹配表单", inputSchema={}),
        ToolDefinition(name="query_form_data_list", description="查询表单数据", inputSchema={}),
        ToolDefinition(name="batch_get_form_value_detail", description="获取详情", inputSchema={}),
    ]


def _quality_intent_with_time_range():
    """模拟'罗诊项目上个月的质量情况'的意图"""
    return DomainIntent(
        domains=["quality"],
        projectHints=["罗诊"],
        timeRange=TimeRange(
            label="上个月",
            start="2026-06-01 00:00:00",
            end="2026-06-30 23:59:59",
            timezone="Asia/Shanghai",
            source="explicit_month"
        ),
        depth="standard",
        confidence=0.9
    )


def test_query_includes_time_filter():
    """查询包含时间过滤条件"""
    candidates = [
        {"formId": "form_quality_defects", "formName": "质量缺陷清单", "matchScore": 0.95},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent_with_time_range(),
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=_config()
    )
    tc = result.toolCalls[0]
    assert tc.toolName == "query_form_data_list"
    assert "filter" in tc.arguments
    filter_val = tc.arguments["filter"]
    # Time range should be in filter
    assert "createTime" in filter_val or "2026-06" in str(filter_val)


def test_query_includes_pagination_intent():
    """查询包含分页意图"""
    candidates = [
        {"formId": "form_quality_defects", "formName": "质量缺陷清单", "matchScore": 0.95},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent_with_time_range(),
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=_config()
    )
    tc = result.toolCalls[0]
    assert tc.pagination is not None
    assert tc.pagination.get("mode") == "auto"
    assert tc.pagination.get("pageSize") == 100
    assert tc.pagination.get("maxPages") == 50
    assert tc.pagination.get("maxItems") == 5000


def test_query_includes_form_id():
    """查询包含formId"""
    candidates = [
        {"formId": "form_abc_123", "formName": "质量缺陷清单", "matchScore": 0.95},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent_with_time_range(),
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=_config()
    )
    tc = result.toolCalls[0]
    assert tc.arguments.get("formId") == "form_abc_123"


def test_multiple_forms_generate_multiple_queries():
    """多个表单生成多个查询"""
    candidates = [
        {"formId": "form_1", "formName": "质量缺陷清单", "matchScore": 0.95},
        {"formId": "form_2", "formName": "重点质量关注项", "matchScore": 0.85},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent_with_time_range(),
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=_config()
    )
    query_calls = [tc for tc in result.toolCalls if tc.toolName == "query_form_data_list"]
    assert len(query_calls) == 2


def test_each_query_has_purpose():
    """每个查询都有purpose说明"""
    candidates = [
        {"formId": "form_1", "formName": "质量缺陷清单", "matchScore": 0.95},
        {"formId": "form_2", "formName": "重点质量关注项", "matchScore": 0.85},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent_with_time_range(),
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=_config()
    )
    for tc in result.toolCalls:
        assert tc.purpose is not None
        assert len(tc.purpose) > 0


def test_page_size_defaults_to_config():
    """pageSize默认使用配置值"""
    candidates = [
        {"formId": "form_1", "formName": "质量缺陷清单", "matchScore": 0.95},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=_quality_intent_with_time_range(),
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=_config()
    )
    assert result.toolCalls[0].arguments.get("pageSize") == 100


def test_end_to_end_quality_scenario():
    """端到端：罗诊项目上个月的质量情况生成正确的查询计划"""
    # Simulate the full pipeline
    question = "罗诊项目上个月的质量情况"
    config = _config()

    # Step 1: Detect domain
    intent = detect_domain_intent(question, config)
    assert "quality" in intent.domains

    # Step 2: Resolve time (simulated result)
    intent.timeRange = TimeRange(
        label="上个月",
        start="2026-06-01 00:00:00",
        end="2026-06-30 23:59:59",
        timezone="Asia/Shanghai",
        source="explicit_month"
    )

    # Step 3: Generate queries for quality core forms
    candidates = [
        {"formId": "form_quality_defects", "formName": "质量缺陷清单", "matchScore": 0.95},
        {"formId": "form_quality_focus", "formName": "重点质量关注项", "matchScore": 0.85},
    ]
    result = plan_form_data_queries(
        candidates=candidates,
        intent=intent,
        project_ids=["roche"],
        available_tools=_available_tools(),
        config=config
    )

    # Should have 2 queries
    assert len(result.toolCalls) == 2
    assert result.needClarification is False

    # Each query should have pagination and time filter
    for tc in result.toolCalls:
        assert tc.toolName == "query_form_data_list"
        assert tc.pagination is not None
        assert tc.pagination["mode"] == "auto"
        assert "filter" in tc.arguments
