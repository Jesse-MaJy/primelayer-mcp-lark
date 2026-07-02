"""Tests for evaluation metrics computation."""
import pytest
from app.evaluation import (
    compute_tool_selection_accuracy,
    compute_time_range_accuracy,
    compute_core_form_accuracy,
    compute_answer_scope_coverage,
    compute_failure_disclosure_coverage,
    evaluate_case,
    evaluate_all,
    EvaluationResult,
)


def _mock_prediction():
    """Mock a perfect prediction."""
    return {
        "domains": ["quality"],
        "projectIds": ["roche"],
        "timeRange": {
            "start": "2026-06-01 00:00:00",
            "end": "2026-06-30 23:59:59"
        },
        "toolCalls": [
            {"toolName": "match_form_resource"},
            {"toolName": "query_form_data_list"}
        ],
        "coreForms": ["质量缺陷清单", "重点质量关注项"],
        "answer": "结论：上个月质量情况良好。关键指标：共150条记录。数据范围：2026-06-01至2026-06-30。建议：加强整改跟进。"
    }


def _mock_case():
    """A sample evaluation case."""
    return {
        "id": "quality_roche_last_month",
        "question": "罗诊项目上个月的质量情况",
        "expectedDomains": ["quality"],
        "expectedProjectIds": ["roche"],
        "expectedTimeRange": {
            "start": "2026-06-01 00:00:00",
            "end": "2026-06-30 23:59:59"
        },
        "expectedToolSequence": ["match_form_resource", "query_form_data_list"],
        "expectedCoreForms": ["质量缺陷清单", "重点质量关注项"],
        "expectedAnswerMustContain": ["数据范围", "质量", "建议"],
        "humanScores": {
            "accuracy": None,
            "completeness": None,
            "insight": None,
            "dataScope": None
        }
    }


class TestToolSelectionAccuracy:
    def test_perfect_match(self):
        assert compute_tool_selection_accuracy(
            ["match_form_resource", "query_form_data_list"],
            ["match_form_resource", "query_form_data_list"]
        ) == 1.0

    def test_partial_match(self):
        score = compute_tool_selection_accuracy(
            ["match_form_resource", "query_form_data_list"],
            ["match_form_resource", "other_tool"]
        )
        assert 0.0 < score < 1.0

    def test_no_match(self):
        assert compute_tool_selection_accuracy(
            ["match_form_resource"],
            ["completely_different"]
        ) == 0.0

    def test_empty_expected(self):
        assert compute_tool_selection_accuracy([], []) == 1.0


class TestTimeRangeAccuracy:
    def test_perfect_match(self):
        assert compute_time_range_accuracy(
            {"start": "2026-06-01 00:00:00", "end": "2026-06-30 23:59:59"},
            {"start": "2026-06-01 00:00:00", "end": "2026-06-30 23:59:59"}
        ) == 1.0

    def test_start_mismatch(self):
        score = compute_time_range_accuracy(
            {"start": "2026-06-01 00:00:00", "end": "2026-06-30 23:59:59"},
            {"start": "2026-06-15 00:00:00", "end": "2026-06-30 23:59:59"}
        )
        assert score < 1.0

    def test_null_prediction(self):
        assert compute_time_range_accuracy(None, {"start": "2026-06-01", "end": "2026-06-30"}) == 0.0

    def test_null_expected(self):
        assert compute_time_range_accuracy({"start": "2026-06-01"}, None) == 1.0  # no expectation = no penalty


class TestCoreFormAccuracy:
    def test_perfect_match(self):
        assert compute_core_form_accuracy(
            ["质量缺陷清单", "重点质量关注项"],
            ["质量缺陷清单", "重点质量关注项"]
        ) == 1.0

    def test_partial_match(self):
        score = compute_core_form_accuracy(
            ["质量缺陷清单"],                      # predicted (missing one)
            ["质量缺陷清单", "重点质量关注项"]      # expected (has 2)
        )
        assert 0.0 < score < 1.0

    def test_extra_form_not_penalized(self):
        # Having extra forms beyond expected should not penalize heavily
        score = compute_core_form_accuracy(
            ["质量缺陷清单", "重点质量关注项", "质量巡检"],
            ["质量缺陷清单", "重点质量关注项"]
        )
        assert score >= 0.8  # Extra forms shouldn't reduce score below 80%


class TestAnswerScopeCoverage:
    def test_all_covered(self):
        assert compute_answer_scope_coverage(
            "结论：良好。数据范围：2026-06-01至2026-06-30。建议：改进。",
            ["数据范围", "建议"]
        ) == 1.0

    def test_partial_coverage(self):
        score = compute_answer_scope_coverage(
            "结论：良好。数据范围：2026-06-01至2026-06-30。",
            ["数据范围", "建议"]
        )
        assert 0.0 < score < 1.0

    def test_missing_data_range_drops_score(self):
        """缺少数据范围时分数下降"""
        score = compute_answer_scope_coverage(
            "结论：良好，质量情况稳定。",
            ["数据范围", "结论"]
        )
        assert score < 1.0  # Missing "数据范围"


class TestFailureDisclosureCoverage:
    def test_no_failures(self):
        # No failures = no disclosure needed = perfect score
        assert compute_failure_disclosure_coverage(
            failed_sources=[],
            answer="结论：良好。数据范围：6月。"
        ) == 1.0

    def test_failure_disclosed(self):
        """失败已说明"""
        score = compute_failure_disclosure_coverage(
            failed_sources=["安全检查表 (MCP超时)"],
            answer="未覆盖：安全检查表因MCP超时无法获取数据。"
        )
        assert score > 0.5

    def test_missing_failure_disclosure_drops_score(self):
        """缺少失败说明时分数下降"""
        score = compute_failure_disclosure_coverage(
            failed_sources=["安全检查表 (MCP超时)"],
            answer="结论：良好。数据范围：6月。"
        )
        assert score < 1.0  # Failed to disclose the failure


class TestEvaluateCase:
    def test_evaluate_single_case(self):
        result = evaluate_case(_mock_case(), _mock_prediction())
        assert isinstance(result, EvaluationResult) or isinstance(result, dict)
        # Should have all 5 metrics
        metrics = result if isinstance(result, dict) else result.__dict__
        assert "tool_selection_accuracy" in metrics or hasattr(result, "tool_selection_accuracy")

    def test_evaluate_all_cases(self):
        cases = [_mock_case(), _mock_case()]
        predictions = [_mock_prediction(), _mock_prediction()]
        summary = evaluate_all(cases, predictions)
        assert "tool_selection_accuracy" in summary
        assert "time_range_accuracy" in summary
        assert "core_form_accuracy" in summary
        assert "answer_scope_coverage" in summary
        assert "failure_disclosure_coverage" in summary
        # All scores between 0 and 1
        for key in summary:
            assert 0.0 <= summary[key] <= 1.0, f"{key} = {summary[key]} out of range"
