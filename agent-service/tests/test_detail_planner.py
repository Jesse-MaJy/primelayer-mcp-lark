"""Tests for DetailEnrichmentPlanner (Task P8)."""

from app.models import ToolCall, ToolDefinition
from app.detail_planner import plan_detail_enrichment, should_fetch_detail


def _available_tools():
    return [
        ToolDefinition(name="batch_get_form_value_detail", description="批量获取表单详情", inputSchema={}),
    ]


def _mock_list_result(form_id="form_1", count=5):
    """Mock query_form_data_list result"""
    records = []
    for i in range(count):
        records.append({
            "dataId": f"data_{form_id}_{i}",
            "formId": form_id,
            "status": "待整改" if i < 2 else "已完成",
            "severity": "高" if i == 0 else "中",
            "isOverdue": i == 1,
        })
    return {"data": {"items": records, "total": count}}


def test_extract_data_ids_from_list_results():
    """从列表结果提取dataId"""
    result = plan_detail_enrichment(
        tool_results=[_mock_list_result("form_1", 5)],
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    assert len(result.toolCalls) == 1
    tc = result.toolCalls[0]
    assert tc.toolName == "batch_get_form_value_detail"
    # Should have extracted dataIds
    assert "dataIds" in tc.arguments
    assert len(tc.arguments["dataIds"]) == 5


def test_prioritize_unclosed_and_high_severity():
    """优先选择未闭环、高严重度记录"""
    records = [
        {"dataId": "d1", "status": "已完成", "severity": "低"},
        {"dataId": "d2", "status": "待整改", "severity": "高"},  # should be first
        {"dataId": "d3", "status": "已完成", "severity": "中"},
        {"dataId": "d4", "status": "逾期", "severity": "高"},    # should be second
    ]
    result = {"data": {"items": records, "total": 4}}

    detail_result = plan_detail_enrichment(
        tool_results=[result],
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    data_ids = detail_result.toolCalls[0].arguments["dataIds"]
    # d2 and d4 should be first (high priority)
    assert data_ids[0] in ("d2", "d4")


def test_max_per_form_limit():
    """每个表单最多20条详情"""
    result = plan_detail_enrichment(
        tool_results=[_mock_list_result("form_1", 50)],
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    data_ids = result.toolCalls[0].arguments["dataIds"]
    assert len(data_ids) <= 20


def test_max_total_limit():
    """多表单总详情最多80条"""
    results = []
    for i in range(5):
        results.append(_mock_list_result(f"form_{i}", 30))

    result = plan_detail_enrichment(
        tool_results=results,
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    total_ids = 0
    for tc in result.toolCalls:
        total_ids += len(tc.arguments["dataIds"])
    assert total_ids <= 80


def test_skip_already_fetched():
    """不重复查询已获取的详情"""
    already = {"data_form_1_0", "data_form_1_1"}
    result = plan_detail_enrichment(
        tool_results=[_mock_list_result("form_1", 5)],
        already_fetched=already,
        max_per_form=20,
        max_total=80
    )
    data_ids = result.toolCalls[0].arguments["dataIds"]
    for fid in already:
        assert fid not in data_ids


def test_no_data_ids_returns_empty():
    """无dataId时不生成详情调用"""
    empty_result = {"data": {"items": [], "total": 0}}
    result = plan_detail_enrichment(
        tool_results=[empty_result],
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    assert len(result.toolCalls) == 0


def test_empty_tool_results():
    """空工具结果不生成调用"""
    result = plan_detail_enrichment(
        tool_results=[],
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    assert len(result.toolCalls) == 0


def test_detail_calls_include_purpose():
    """详情调用包含purpose"""
    result = plan_detail_enrichment(
        tool_results=[_mock_list_result("form_1", 3)],
        already_fetched=set(),
        max_per_form=20,
        max_total=80
    )
    if result.toolCalls:
        assert result.toolCalls[0].purpose is not None


def test_should_fetch_detail():
    """should_fetch_detail判断逻辑"""
    # High severity + unclosed → should fetch
    assert should_fetch_detail({"severity": "高", "status": "待整改"}) is True
    # Overdue → should fetch
    assert should_fetch_detail({"isOverdue": True}) is True
    # Completed + low severity → skip
    assert should_fetch_detail({"status": "已完成", "severity": "低"}) is False
    # Unknown status → fetch to be safe
    assert should_fetch_detail({}) is True
