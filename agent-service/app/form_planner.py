"""Form Discovery Planner for matching and ranking forms by domain intent.

Generates tool calls to discover forms relevant to a detected domain intent,
ranks candidate forms by their relevance to the domain, and produces data
query tool calls for high-confidence matches.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from app.domain_config import AppDomainConfig
from app.models import DomainIntent, ToolCall, ToolDefinition


class FormPlanResult(BaseModel):
    """Result of planning form data queries from ranked candidates."""

    toolCalls: list[ToolCall] = []
    needClarification: bool = False
    clarificationQuestion: str | None = None


def plan_form_discovery(
    intent: DomainIntent,
    project_ids: list[str],
    available_tools: list[ToolDefinition],
    config: AppDomainConfig,
    tool_results: list[dict[str, Any]] | None = None,
) -> list[ToolCall]:
    """Generate tool calls for the current phase of form discovery.

    Phase 1 (no tool_results): Generate match_form_resource calls for each
    core form in the matched domains.

    Phase 2 (tool_results provided): Extract form candidates from prior
    match_form_resource results, rank them, and generate data query calls.
    """
    if not tool_results:
        return _phase1_match_form_calls(intent, project_ids, available_tools, config)

    # Phase 2: extract candidates from match_form_resource results
    candidates = _extract_candidates_from_results(tool_results)
    if not candidates:
        return []

    # Determine the primary domain for ranking
    primary_domain = intent.domains[0] if intent.domains else "general"
    ranked = rank_candidate_forms(candidates, primary_domain, config)

    # Generate data queries
    plan_result = plan_form_data_queries(
        candidates=ranked,
        intent=intent,
        project_ids=project_ids,
        available_tools=available_tools,
        config=config,
    )
    return plan_result.toolCalls


def rank_candidate_forms(
    candidates: list[dict[str, Any]],
    domain: str,
    config: AppDomainConfig,
) -> list[dict[str, Any]]:
    """Rank form candidates by relevance to the given domain.

    Ranking priority (highest to lowest):
    1. Core form exact match
    2. Supplemental form match (substring)
    3. Unmatched / unknown
    4. Exclude form match (substring) -- deprioritized

    Within each group, candidates are sorted by matchScore descending.
    Returns a new list with ranking metadata added to each candidate.
    """
    domain_config = config.domains.get(domain)
    if not domain_config:
        # No domain config -- sort by matchScore only
        return sorted(candidates, key=lambda c: c.get("matchScore", 0), reverse=True)

    core_forms = domain_config.coreForms
    supplemental_forms = domain_config.supplementalForms
    exclude_forms = domain_config.excludeForms

    def _rank_group(candidate: dict[str, Any]) -> tuple[int, float]:
        """Return (group_priority, negated_score) for sorting.

        Group 0 = core, 1 = supplemental, 2 = unmatched, 3 = excluded.
        Lower group number = higher priority.
        """
        name = candidate.get("formName", "")
        score = candidate.get("matchScore", 0.0)

        # Check core forms (exact match)
        for core_form in core_forms:
            if name == core_form:
                return (0, -score)

        # Check supplemental forms (substring match)
        for supp_form in supplemental_forms:
            if supp_form in name or name in supp_form:
                return (1, -score)

        # Check exclude forms (substring match)
        for excl_form in exclude_forms:
            if excl_form in name or name in excl_form:
                return (3, -score)

        # Unmatched
        return (2, -score)

    ranked = sorted(candidates, key=_rank_group)

    # Add ranking metadata
    for candidate in ranked:
        group, _ = _rank_group(candidate)
        group_names = {0: "core", 1: "supplemental", 2: "unmatched", 3: "excluded"}
        candidate["rankGroup"] = group_names.get(group, "unmatched")
        candidate["rankReason"] = _rank_reason(candidate, group_names.get(group, "unmatched"), domain_config)

    return ranked


def plan_form_data_queries(
    candidates: list[dict[str, Any]],
    intent: DomainIntent,
    project_ids: list[str],
    available_tools: list[ToolDefinition],
    config: AppDomainConfig,
) -> FormPlanResult:
    """Generate query_form_data_list calls for high-confidence candidates.

    Candidates with matchScore >= 0.5 are considered high-confidence and
    will have data query calls generated for them. If no candidates meet
    the threshold, a clarification result is returned.
    """
    # Check if query_form_data_list tool is available
    if not _has_tool(available_tools, "query_form_data_list"):
        return FormPlanResult(
            toolCalls=[],
            needClarification=False,
        )

    # Filter for high-confidence candidates (matchScore >= 0.5)
    high_confidence = [c for c in candidates if c.get("matchScore", 0) >= 0.5]

    if not high_confidence:
        return FormPlanResult(
            toolCalls=[],
            needClarification=True,
            clarificationQuestion=_build_clarification_question(candidates, intent),
        )

    # Build time range filter
    time_filter: dict[str, Any] = {}
    if intent.timeRange:
        time_filter = {
            "createTime": [intent.timeRange.start, intent.timeRange.end],
        }

    tool_calls: list[ToolCall] = []
    for candidate in high_confidence:
        form_id = str(candidate.get("formId", ""))
        form_name = str(candidate.get("formName", ""))
        if not form_id:
            continue

        arguments: dict[str, Any] = {
            "formId": form_id,
            "page": 1,
            "pageSize": config.pagination.defaultPageSize,
        }
        if time_filter:
            arguments["filter"] = time_filter

        # Build pagination intent from config defaults
        pagination: dict[str, Any] = {
            "mode": "auto",
            "pageSize": config.pagination.defaultPageSize,
            "maxPages": config.pagination.maxPages,
            "maxItems": config.pagination.maxItems,
        }

        # Build descriptive purpose: 查询[表单名]在[时间范围]的数据
        time_label = intent.timeRange.label if intent.timeRange else "指定时间段"
        purpose = f"查询{form_name}在{time_label}的数据"

        # Build reason with form ranking context
        group = candidate.get("rankGroup", "form")
        reason = f"查询表单[{form_name}]在{time_label}的数据，匹配等级: {group}"

        tool_calls.append(
            ToolCall(
                toolName="query_form_data_list",
                arguments=arguments,
                projectIds=project_ids,
                reason=reason,
                purpose=purpose,
                pagination=pagination,
            )
        )

    return FormPlanResult(
        toolCalls=tool_calls,
        needClarification=False,
    )


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _phase1_match_form_calls(
    intent: DomainIntent,
    project_ids: list[str],
    available_tools: list[ToolDefinition],
    config: AppDomainConfig,
) -> list[ToolCall]:
    """Generate match_form_resource calls for core forms in matched domains."""
    if not _has_tool(available_tools, "match_form_resource"):
        return []

    calls: list[ToolCall] = []
    for domain_name in intent.domains:
        if domain_name == "general":
            continue
        domain_config = config.domains.get(domain_name)
        if not domain_config:
            continue
        for form_name in domain_config.coreForms:
            calls.append(
                ToolCall(
                    toolName="match_form_resource",
                    arguments={"name": form_name},
                    projectIds=project_ids,
                    reason=f"匹配{domain_name}领域核心表单[{form_name}]",
                    purpose=f"discover_{domain_name}_core_forms",
                )
            )

    return calls


def _extract_candidates_from_results(
    tool_results: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Extract form candidates from match_form_resource tool results."""
    candidates: list[dict[str, Any]] = []
    for item in tool_results:
        if item.get("toolName") != "match_form_resource":
            continue
        if not _is_success(item):
            continue
        for form in _extract_forms(item.get("result")):
            form_id = str(form.get("formId") or form.get("id") or "")
            form_name = str(
                form.get("formName")
                or form.get("formTitle")
                or form.get("title")
                or form.get("name")
                or ""
            )
            if not form_id or not form_name:
                continue
            candidates.append(
                {
                    "formId": form_id,
                    "formName": form_name,
                    "matchScore": float(form.get("matchScore") or form.get("score") or 0.5),
                }
            )
    return candidates


def _extract_forms(value: Any) -> list[dict[str, Any]]:
    """Recursively walk a value tree and extract form-like dicts."""
    forms: list[dict[str, Any]] = []
    for item in _walk(value):
        if not isinstance(item, dict):
            continue
        has_form_id = any(key in item for key in ("formId", "form_id", "id"))
        has_form_name = any(key in item for key in ("formName", "formTitle", "title", "name"))
        if has_form_id and has_form_name:
            forms.append(item)
    return forms


def _walk(value: Any) -> list[Any]:
    """Recursively flatten a nested structure into a list of leaf values."""
    items = [value]
    if isinstance(value, dict):
        for child in value.values():
            items.extend(_walk(child))
    elif isinstance(value, list):
        for child in value:
            items.extend(_walk(child))
    return items


def _has_tool(tools: list[ToolDefinition], name: str) -> bool:
    """Check whether a tool with the given name exists in the available tools."""
    return any(tool.name == name for tool in tools)


def _is_success(item: dict[str, Any]) -> bool:
    """Check if a tool result item has a succeeded status."""
    return str(item.get("status", "")).upper() == "SUCCEEDED"


def _rank_reason(
    candidate: dict[str, Any],
    group: str,
    domain_config: Any,
) -> str:
    """Build a human-readable reason for the ranking decision."""
    name = candidate.get("formName", "")
    if group == "core":
        return f"表单[{name}]匹配领域核心表单"
    elif group == "supplemental":
        return f"表单[{name}]匹配领域补充表单"
    elif group == "excluded":
        return f"表单[{name}]匹配领域排除关键词，降权处理"
    return f"表单[{name}]未匹配领域表单配置"


def _build_clarification_question(
    candidates: list[dict[str, Any]],
    intent: DomainIntent,
) -> str:
    """Build a clarification question when no high-confidence candidates found."""
    domains_str = "、".join(intent.domains) if intent.domains else "相关"
    if candidates:
        names = [c.get("formName", "") for c in candidates[:3]]
        names_str = "、".join(names)
        return f"在{domains_str}领域找到以下表单（{names_str}），但匹配置信度较低，请确认是否需要查询这些表单的数据？"
    return f"未能找到与{domains_str}领域明确匹配的表单，请提供更多信息以便定位合适的表单。"
