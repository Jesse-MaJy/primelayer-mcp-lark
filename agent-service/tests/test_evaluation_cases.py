"""Validate evaluation cases completeness and format."""
import json
from pathlib import Path


def _load_cases():
    cases_path = Path(__file__).parent.parent / "evaluation" / "project_agent_cases.json"
    with open(cases_path) as f:
        return json.load(f)


REQUIRED_FIELDS = [
    "id", "question", "expectedDomains", "expectedProjectIds",
    "expectedTimeRange", "expectedToolSequence", "expectedCoreForms",
    "expectedAnswerMustContain", "humanScores"
]

VALID_DOMAINS = {"quality", "safety", "progress", "risk", "general"}


def test_case_count():
    """样例数量不少于30条"""
    cases = _load_cases()
    assert len(cases) >= 30, f"Expected >= 30 cases, got {len(cases)}"


def test_all_cases_have_required_fields():
    """每条样例包含必填字段"""
    cases = _load_cases()
    for case in cases:
        for field in REQUIRED_FIELDS:
            assert field in case, f"Case {case.get('id', 'unknown')} missing field: {field}"


def test_all_cases_have_valid_domains():
    """所有样例的expectedDomains是有效业务域"""
    cases = _load_cases()
    for case in cases:
        for domain in case["expectedDomains"]:
            assert domain in VALID_DOMAINS, f"Case {case['id']}: invalid domain {domain}"


def test_all_cases_have_tool_sequence():
    """每条样例包含至少一个工具调用"""
    cases = _load_cases()
    for case in cases:
        assert len(case["expectedToolSequence"]) > 0, f"Case {case['id']}: empty tool sequence"


def test_all_cases_have_answer_requirements():
    """每条样例包含回答要点"""
    cases = _load_cases()
    for case in cases:
        assert len(case["expectedAnswerMustContain"]) > 0, f"Case {case['id']}: empty answer requirements"


def test_coverage_all_domains():
    """覆盖所有四个业务域"""
    cases = _load_cases()
    all_domains = set()
    for case in cases:
        all_domains.update(case["expectedDomains"])
    assert "quality" in all_domains
    assert "safety" in all_domains
    assert "progress" in all_domains
    assert "risk" in all_domains


def test_coverage_key_scenarios():
    """覆盖关键场景"""
    cases = _load_cases()
    case_ids = {c["id"] for c in cases}
    required = [
        "quality_roche_last_month",
        "safety_this_week",
        "progress_current",
        "risk_overdue"
    ]
    for rid in required:
        assert rid in case_ids, f"Missing required case: {rid}"


def test_unique_ids():
    """所有样例ID唯一"""
    cases = _load_cases()
    ids = [c["id"] for c in cases]
    assert len(ids) == len(set(ids)), "Duplicate case IDs found"
