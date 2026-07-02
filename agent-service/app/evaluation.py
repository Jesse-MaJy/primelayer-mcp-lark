"""
Evaluation metrics for project agent intelligence.

Metrics:
- tool_selection_accuracy: Jaccard similarity of predicted vs expected tools
- time_range_accuracy: 1.0 if exact match, partial credit for partial match
- core_form_accuracy: Recall of expected core forms in predicted forms
- answer_scope_coverage: Fraction of required answer elements present
- failure_disclosure_coverage: Whether failures are disclosed in answer
"""
from dataclasses import dataclass


@dataclass
class EvaluationResult:
    case_id: str
    tool_selection_accuracy: float
    time_range_accuracy: float
    core_form_accuracy: float
    answer_scope_coverage: float
    failure_disclosure_coverage: float


def compute_tool_selection_accuracy(predicted: list[str], expected: list[str]) -> float:
    """Jaccard similarity between predicted and expected tool sequences."""
    if not expected:
        return 1.0
    pred_set = set(predicted)
    exp_set = set(expected)
    intersection = pred_set & exp_set
    union = pred_set | exp_set
    if not union:
        return 1.0
    return len(intersection) / len(union)


def compute_time_range_accuracy(predicted: dict | None, expected: dict | None) -> float:
    """Compare predicted time range with expected."""
    if expected is None:
        return 1.0  # No expectation set
    if predicted is None:
        return 0.0
    # Compare start and end
    start_match = predicted.get("start") == expected.get("start")
    end_match = predicted.get("end") == expected.get("end")
    if start_match and end_match:
        return 1.0
    if start_match or end_match:
        return 0.5
    return 0.0


def compute_core_form_accuracy(predicted: list[str], expected: list[str]) -> float:
    """Recall: what fraction of expected core forms were found."""
    if not expected:
        return 1.0
    pred_set = set(predicted)
    found = sum(1 for f in expected if f in pred_set)
    return found / len(expected)


def compute_answer_scope_coverage(answer: str, required_elements: list[str]) -> float:
    """What fraction of required answer elements appear in the answer."""
    if not required_elements:
        return 1.0
    found = sum(1 for elem in required_elements if elem in answer)
    return found / len(required_elements)


def compute_failure_disclosure_coverage(failed_sources: list[str], answer: str) -> float:
    """Whether failures are properly disclosed in the answer."""
    if not failed_sources:
        return 1.0
    # Check if answer mentions failures or has "未覆盖"/"失败" section
    has_disclosure = any(
        keyword in answer
        for keyword in ["未覆盖", "失败说明", "失败", "无法获取", "超时"]
    )
    if has_disclosure:
        # Check if specific failed sources are mentioned
        mentioned = sum(1 for src in failed_sources if any(
            word in answer for word in src.split() if len(word) >= 2
        ))
        return 0.5 + 0.5 * (mentioned / len(failed_sources))
    return 0.0


def evaluate_case(case: dict, prediction: dict) -> EvaluationResult:
    """Evaluate a single case against its prediction."""
    return EvaluationResult(
        case_id=case["id"],
        tool_selection_accuracy=compute_tool_selection_accuracy(
            [tc.get("toolName", "") for tc in prediction.get("toolCalls", [])],
            case.get("expectedToolSequence", [])
        ),
        time_range_accuracy=compute_time_range_accuracy(
            prediction.get("timeRange"),
            case.get("expectedTimeRange")
        ),
        core_form_accuracy=compute_core_form_accuracy(
            prediction.get("coreForms", []),
            case.get("expectedCoreForms", [])
        ),
        answer_scope_coverage=compute_answer_scope_coverage(
            prediction.get("answer", ""),
            case.get("expectedAnswerMustContain", [])
        ),
        failure_disclosure_coverage=compute_failure_disclosure_coverage(
            prediction.get("failedSources", []),
            prediction.get("answer", "")
        )
    )


def evaluate_all(cases: list[dict], predictions: list[dict]) -> dict[str, float]:
    """Evaluate all cases and return average scores."""
    assert len(cases) == len(predictions), "Mismatched lengths"

    results = [evaluate_case(c, p) for c, p in zip(cases, predictions)]

    n = len(results)
    return {
        "tool_selection_accuracy": sum(r.tool_selection_accuracy for r in results) / n,
        "time_range_accuracy": sum(r.time_range_accuracy for r in results) / n,
        "core_form_accuracy": sum(r.core_form_accuracy for r in results) / n,
        "answer_scope_coverage": sum(r.answer_scope_coverage for r in results) / n,
        "failure_disclosure_coverage": sum(r.failure_disclosure_coverage for r in results) / n,
    }
