"""Tests for MetricSummarizer (Task P9)."""

import pytest
from app.models import DomainIntent, TimeRange
from app.metric_summarizer import (
    compute_quality_metrics,
    compute_safety_metrics,
    compute_progress_metrics,
    compute_risk_metrics,
    build_management_report,
)


def _mock_quality_data():
    """Mock quality form data with defects of varying severity and status"""
    return {
        "total": 150,
        "items": [
            {"dataId": "d1", "status": "待整改", "severity": "高", "type": "结构缺陷", "responsiblePerson": "张三", "area": "A区"},
            {"dataId": "d2", "status": "待整改", "severity": "高", "type": "结构缺陷", "responsiblePerson": "张三", "area": "A区"},
            {"dataId": "d3", "status": "待整改", "severity": "中", "type": "电气问题", "responsiblePerson": "李四", "area": "B区"},
            {"dataId": "d4", "status": "已完成", "severity": "中", "type": "电气问题", "responsiblePerson": "王五", "area": "C区"},
            {"dataId": "d5", "status": "已完成", "severity": "低", "type": "装饰问题", "responsiblePerson": "赵六", "area": "A区"},
            {"dataId": "d6", "status": "逾期未整改", "severity": "高", "type": "结构缺陷", "responsiblePerson": "李四", "area": "B区"},
        ]
    }


def _mock_tool_results():
    return [
        {
            "toolName": "query_form_data_list",
            "formName": "质量缺陷清单",
            "data": _mock_quality_data(),
            "status": "success"
        }
    ]


def test_compute_quality_metrics_basic():
    """质量域基础指标统计"""
    metrics = compute_quality_metrics(_mock_quality_data())
    assert metrics["total_records"] == 150
    assert metrics["unclosed_count"] > 0
    assert metrics["high_severity_count"] > 0


def test_compute_quality_metrics_status_distribution():
    """质量域状态分布"""
    metrics = compute_quality_metrics(_mock_quality_data())
    assert "status_distribution" in metrics
    dist = metrics["status_distribution"]
    assert "待整改" in dist
    assert "已完成" in dist


def test_compute_quality_metrics_does_not_treat_unknown_status_as_unclosed():
    """状态字段缺失时不能把 total 全部算成未整改。"""
    metrics = compute_quality_metrics({
        "total": 4,
        "items": [
            {"dataId": "d1", "status": "待整改", "severity": "高"},
            {"dataId": "d2", "status": "已完成", "severity": "低"},
            {"dataId": "d3"},
            {"dataId": "d4"},
        ],
    })

    assert metrics["total_records"] == 4
    assert metrics["known_status_count"] == 2
    assert metrics["unknown_status_count"] == 2
    assert metrics["unclosed_count"] == 1
    assert metrics["completed_count"] == 1


def test_compute_quality_metrics_type_distribution():
    """质量域问题类型分布"""
    metrics = compute_quality_metrics(_mock_quality_data())
    assert "type_distribution" in metrics
    # 结构缺陷 should be most common
    assert metrics["type_distribution"]["结构缺陷"] >= 2


def test_compute_quality_metrics_responsible_distribution():
    """质量域责任人分布"""
    metrics = compute_quality_metrics(_mock_quality_data())
    assert "responsible_distribution" in metrics


def test_build_management_report_quality():
    """生成质量域管理报告"""
    report = build_management_report(
        domain="quality",
        metrics={
            "total_records": 150,
            "unclosed_count": 45,
            "high_severity_count": 12,
            "status_distribution": {"待整改": 30, "已完成": 105, "逾期未整改": 15},
            "type_distribution": {"结构缺陷": 80, "电气问题": 40, "装饰问题": 30},
            "responsible_distribution": {"张三": 50, "李四": 60, "王五": 40},
        },
        data_sources=["质量缺陷清单 (2026-06-01 至 2026-06-30)"],
        failed_sources=[],
        domain_intent=DomainIntent(
            domains=["quality"],
            projectHints=["罗诊"],
            timeRange=TimeRange(label="上个月", start="2026-06-01 00:00:00", end="2026-06-30 23:59:59", timezone="Asia/Shanghai", source="explicit_month"),
            depth="standard",
            confidence=0.9
        )
    )
    assert "结论" in report
    assert "关键指标" in report
    assert "数据范围" in report


def test_build_management_report_includes_data_range():
    """报告包含数据时间范围"""
    report = build_management_report(
        domain="quality",
        metrics={"total_records": 10, "unclosed_count": 2, "high_severity_count": 1},
        data_sources=["质量缺陷清单"],
        failed_sources=[],
        domain_intent=DomainIntent(
            domains=["quality"],
            projectHints=[],
            timeRange=TimeRange(label="上个月", start="2026-06-01 00:00:00", end="2026-06-30 23:59:59", timezone="Asia/Shanghai", source="explicit_month"),
            depth="standard",
            confidence=0.8
        )
    )
    assert "2026-06-01" in report
    assert "2026-06-30" in report


def test_build_management_report_with_failures():
    """部分失败时报告包含失败说明"""
    report = build_management_report(
        domain="safety",
        metrics={"total_records": 50, "unclosed_count": 10, "high_risk_count": 3},
        data_sources=["安全隐患清单"],
        failed_sources=["安全检查表 (MCP超时)"],
        domain_intent=DomainIntent(
            domains=["safety"],
            projectHints=[],
            timeRange=TimeRange(label="本周", start="2026-06-29 00:00:00", end="2026-07-05 23:59:59", timezone="Asia/Shanghai", source="explicit_week"),
            depth="standard",
            confidence=0.9
        )
    )
    assert "未覆盖" in report or "失败" in report or "安全检查表" in report


def test_build_management_report_without_timerange():
    """无时间范围时的报告"""
    report = build_management_report(
        domain="quality",
        metrics={"total_records": 10, "unclosed_count": 2, "high_severity_count": 1},
        data_sources=["质量缺陷清单"],
        failed_sources=[],
        domain_intent=DomainIntent(domains=["quality"], confidence=0.5)
    )
    # Should still work without a time range
    assert "结论" in report or len(report) > 0


def test_safety_metrics():
    """安全域指标"""
    safety_data = {
        "total": 80,
        "items": [
            {"dataId": "s1", "riskLevel": "高", "status": "未整改", "area": "A区"},
            {"dataId": "s2", "riskLevel": "高", "status": "未整改", "area": "B区"},
            {"dataId": "s3", "riskLevel": "中", "status": "已整改", "area": "A区"},
            {"dataId": "s4", "riskLevel": "低", "status": "已整改", "area": "C区"},
            {"dataId": "s5", "riskLevel": "高", "status": "逾期未整改", "area": "B区"},
        ]
    }
    metrics = compute_safety_metrics(safety_data)
    assert metrics["total_records"] == 80
    assert metrics["high_risk_count"] == 3
    assert metrics["unrectified_count"] >= 2


def test_progress_metrics():
    """进度域指标"""
    progress_data = {
        "total": 30,
        "items": [
            {"dataId": "p1", "planProgress": 80, "actualProgress": 60, "status": "滞后"},
            {"dataId": "p2", "planProgress": 50, "actualProgress": 50, "status": "正常"},
            {"dataId": "p3", "planProgress": 90, "actualProgress": 70, "status": "滞后"},
        ]
    }
    metrics = compute_progress_metrics(progress_data)
    assert metrics["total_nodes"] == 30
    assert metrics["lagging_count"] >= 2


def test_risk_metrics():
    """风险域指标"""
    risk_data = {
        "total": 25,
        "items": [
            {"dataId": "r1", "riskLevel": "高", "status": "未处理", "isOverdue": True},
            {"dataId": "r2", "riskLevel": "中", "status": "处理中", "isOverdue": False},
            {"dataId": "r3", "riskLevel": "高", "status": "未处理", "isOverdue": True},
            {"dataId": "r4", "riskLevel": "低", "status": "已关闭", "isOverdue": False},
        ]
    }
    metrics = compute_risk_metrics(risk_data)
    assert metrics["total_risks"] == 25
    assert metrics["high_risk_count"] == 2
    assert metrics["overdue_count"] == 2
    assert metrics["unresolved_count"] == 2


def test_deterministic_fallback():
    """无模型时确定性兜底报告"""
    report = build_management_report(
        domain="quality",
        metrics={"total_records": 5, "unclosed_count": 1, "high_severity_count": 0},
        data_sources=["质量缺陷清单"],
        failed_sources=[],
        domain_intent=DomainIntent(domains=["quality"], confidence=0.5),
        use_model=False  # Force deterministic fallback
    )
    assert len(report) > 0
    assert "建议" in report or "数据范围" in report or "指标" in report
