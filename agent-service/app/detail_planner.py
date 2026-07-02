"""Detail Enrichment Planner for selective detail fetching.

Extracts key record IDs from query_form_data_list results and generates
batch_get_form_value_detail calls for records that need closer inspection
(unclosed, high severity, overdue, blocked).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from app.models import ToolCall

# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


class DetailPlanResult(BaseModel):
    """Result of planning detail enrichment calls."""

    toolCalls: list[ToolCall] = []
    fetched_ids: set[str] = set()


# Statuses considered "closed" -- these records do not need detail fetching
CLOSED_STATUSES = frozenset({"已完成", "已关闭", "已消除", "关闭", "完成", "合格", "通过"})

# Severity levels that warrant attention
HIGH_SEVERITY = frozenset({"高", "严重", "紧急", "critical", "high"})


def should_fetch_detail(record: dict[str, Any]) -> bool:
    """Determine if a record warrants detail fetching.

    Returns True for: unclosed status, high severity, overdue, blocked, or unknown.
    Returns False for: completed + low severity.
    """
    status = str(record.get("status", "")).strip()
    severity = str(record.get("severity", "")).strip()

    # Overdue -- always fetch
    if record.get("isOverdue") or record.get("isOverDue"):
        return True

    # High severity and unclosed -- fetch
    if severity in HIGH_SEVERITY and (not status or status not in CLOSED_STATUSES):
        return True

    # Known unclosed status -- fetch
    if status and status not in CLOSED_STATUSES:
        return True

    # Completed + low severity -- skip
    if status in CLOSED_STATUSES and severity and severity not in HIGH_SEVERITY:
        return False

    # Unknown status -- fetch to be safe
    return True


def plan_detail_enrichment(
    tool_results: list[dict[str, Any]],
    already_fetched: set[str],
    max_per_form: int = 20,
    max_total: int = 80,
) -> DetailPlanResult:
    """Extract dataIds from query_form_data_list results and generate detail calls.

    Args:
        tool_results: Results from prior tool calls (query_form_data_list).
        already_fetched: Set of dataIds already fetched (to skip).
        max_per_form: Maximum detail records per form.
        max_total: Maximum total detail records across all forms.

    Returns:
        DetailPlanResult with generated tool calls and updated fetched_ids set.
    """
    records = _extract_records_from_results(tool_results)
    if not records:
        return DetailPlanResult(toolCalls=[], fetched_ids=already_fetched)

    # Deduplicate by dataId, keeping highest priority
    seen: dict[str, dict[str, Any]] = {}
    for r in records:
        data_id = str(r.get("dataId", ""))
        if not data_id:
            continue
        if data_id not in seen or _priority_score(r) > _priority_score(seen[data_id]):
            seen[data_id] = r

    # Remove already fetched
    for fid in already_fetched:
        seen.pop(fid, None)

    if not seen:
        return DetailPlanResult(toolCalls=[], fetched_ids=already_fetched)

    # Group by formId
    by_form: dict[str, list[dict[str, Any]]] = {}
    for data_id, record in seen.items():
        form_id = str(record.get("formId", "__unknown__"))
        by_form.setdefault(form_id, []).append(record)

    # Within each form: sort by priority descending, apply per-form limit
    all_selected: list[dict[str, Any]] = []
    for form_id, form_records in by_form.items():
        form_records.sort(key=_priority_score, reverse=True)
        selected = form_records[:max_per_form]
        all_selected.extend(selected)

    # Sort all selected by priority descending, apply total limit
    all_selected.sort(key=_priority_score, reverse=True)
    all_selected = all_selected[:max_total]

    # Regroup by formId for tool calls
    by_form_selected: dict[str, list[str]] = {}
    for record in all_selected:
        form_id = str(record.get("formId", "__unknown__"))
        data_id = str(record.get("dataId", ""))
        by_form_selected.setdefault(form_id, []).append(data_id)

    # Generate tool calls
    tool_calls: list[ToolCall] = []
    fetched_ids = set(already_fetched)
    for form_id, data_ids in by_form_selected.items():
        fetched_ids.update(data_ids)
        purpose = f"获取表单[{form_id}]中{len(data_ids)}条记录的详情数据"
        tool_calls.append(
            ToolCall(
                toolName="batch_get_form_value_detail",
                arguments={"dataIds": data_ids, "formId": form_id},
                reason=f"查询表单[{form_id}]的{len(data_ids)}条记录详情，用于深度分析",
                purpose=purpose,
            )
        )

    return DetailPlanResult(toolCalls=tool_calls, fetched_ids=fetched_ids)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _priority_score(record: dict[str, Any]) -> int:
    """Compute a priority score for a record.

    Higher score = higher priority for detail fetching.

    Scoring:
        unclosed/unknown status: +1
        high severity: +2
        overdue: +2
        blocked: +1
    """
    score = 0
    status = str(record.get("status", "")).strip()

    # Unclosed or unknown status
    if not status or status not in CLOSED_STATUSES:
        score += 1

    # High severity
    if str(record.get("severity", "")).strip() in HIGH_SEVERITY:
        score += 2

    # Overdue
    if record.get("isOverdue") or record.get("isOverDue"):
        score += 2

    # Blocked
    if status in ("阻塞", "blocked"):
        score += 1

    return score


def _extract_records_from_results(
    tool_results: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Extract all records that have a dataId from tool results."""
    records: list[dict[str, Any]] = []
    for item in tool_results:
        if not isinstance(item, dict):
            continue
        _walk_for_records(item, records)
    return records


def _walk_for_records(value: Any, records: list[dict[str, Any]]) -> None:
    """Recursively walk a value tree and collect dicts that have dataId."""
    if isinstance(value, dict):
        if "dataId" in value:
            records.append(value)
        for v in value.values():
            _walk_for_records(v, records)
    elif isinstance(value, list):
        for item in value:
            _walk_for_records(item, records)
