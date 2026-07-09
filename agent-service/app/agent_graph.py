from __future__ import annotations

import json
import os
from typing import Any, TypedDict

from .models import AgentAnswerRequest, AgentAnswerResponse, ToolCall, ToolDefinition
from .project_workflow import (
    PROJECT_REPORT_SKILL_ID,
    is_project_report_question,
    plan_project_workflow,
    summarize_project_workflow,
    workflow_metadata,
)
from .skills import SkillDefinition, classify_skill, tool_allowed_for_skill

# Domain workflow modules (optional enhancements --- wrapped in try/except at call sites)
from .domain_config import get_domain_config
from .domain_intent import detect_domain_intent
from .form_planner import plan_form_discovery
from .metric_summarizer import (
    build_management_report,
    compute_quality_metrics,
    compute_safety_metrics,
    compute_progress_metrics,
    compute_risk_metrics,
)
from .project_alias import resolve_project_ids
from .time_range import resolve_time_range

READ_ONLY_PREFIXES = ("get_", "list_", "query_", "search_", "primelayer.query_")


class AgentState(TypedDict, total=False):
    request: AgentAnswerRequest
    skill: SkillDefinition
    project_scope: str
    project_ids: list[str]
    tool_calls: list[ToolCall]
    tool_selection: dict[str, Any]
    response: AgentAnswerResponse


def run_agent(request: AgentAnswerRequest) -> AgentAnswerResponse:
    graph = _build_graph()
    if graph is None:
        state = _quality_check(_summarize_answer(_plan_tool_calls(_select_project_scope(_classify_skill({"request": request})))))
    else:
        state = graph.invoke({"request": request})
    return state["response"]


def _build_graph():
    try:
        from langgraph.graph import END, StateGraph
    except Exception:
        return None

    workflow = StateGraph(AgentState)
    workflow.add_node("classify_skill", _classify_skill)
    workflow.add_node("select_project_scope", _select_project_scope)
    workflow.add_node("plan_tool_calls", _plan_tool_calls)
    workflow.add_node("summarize_answer", _summarize_answer)
    workflow.add_node("quality_check", _quality_check)
    workflow.set_entry_point("classify_skill")
    workflow.add_edge("classify_skill", "select_project_scope")
    workflow.add_edge("select_project_scope", "plan_tool_calls")
    workflow.add_edge("plan_tool_calls", "summarize_answer")
    workflow.add_edge("summarize_answer", "quality_check")
    workflow.add_edge("quality_check", END)
    return workflow.compile()


def _classify_skill(state: AgentState) -> AgentState:
    request = state["request"]
    state["skill"] = classify_skill(request.question)
    return state


def _select_project_scope(state: AgentState) -> AgentState:
    request = state["request"]
    text = (request.question or "").lower()
    projects = request.projects
    if request.chatType == "group":
        state["project_scope"] = "current_chat_project"
        state["project_ids"] = [project.projectId for project in projects]
        return state
    if any(word in text for word in ("所有", "全部", "跨项目", "all projects", "all accessible")):
        state["project_scope"] = "all_accessible_projects"
        state["project_ids"] = [project.projectId for project in projects]
        return state
    matched = [
        project.projectId
        for project in projects
        if _project_matches_question(project.projectId, project.projectName, text)
    ]
    if matched:
        state["project_scope"] = "single_project"
        state["project_ids"] = matched[:1]
        return state
    if len(projects) == 1:
        state["project_scope"] = "single_project"
        state["project_ids"] = [projects[0].projectId]
        return state
    state["project_scope"] = "unknown"
    state["project_ids"] = []
    # Fallback: try ProjectAliasResolver for alias-based matching
    if projects:
        try:
            config = get_domain_config()
            match = resolve_project_ids(request.question, projects, config)
            if not match.needClarification and match.confidence > 0.5:
                state["project_ids"] = match.projectIds
                state["project_scope"] = "single_project"
        except Exception:
            pass  # Alias resolver is an optional enhancement
    return state


def _plan_tool_calls(state: AgentState) -> AgentState:
    request = state["request"]
    skill = state["skill"]
    project_ids = state.get("project_ids", [])
    if not project_ids:
        state["tool_calls"] = []
        return state
    if _uses_project_workflow(request, skill):
        calls = plan_project_workflow(request, project_ids)
        _set_reviewed_tool_plan(state, calls)
        return state
    # Try domain workflow for non-project-workflow questions
    if project_ids:
        try:
            config = get_domain_config()
            intent = detect_domain_intent(request.question, config)
            if "general" not in intent.domains:
                tr = resolve_time_range(request.question, default_domain=intent.domains[0])
                intent.timeRange = tr
                tool_calls = plan_form_discovery(
                    intent=intent,
                    project_ids=project_ids,
                    available_tools=request.availableTools,
                    config=config,
                    tool_results=request.toolResults,
                )
                if tool_calls:
                    _set_reviewed_tool_plan(state, tool_calls)
                    return state
        except Exception:
            pass  # Domain workflow is an optional enhancement
    if request.toolResults:
        state["tool_calls"] = []
        return state
    tool = _select_tool(request.question, skill, request.availableTools)
    if tool is None:
        state["tool_calls"] = []
        return state
    _set_reviewed_tool_plan(state, [
        ToolCall(
            toolName=tool.name,
            arguments=_build_arguments(request.question, tool),
            projectIds=project_ids,
            reason=f"skill={skill.skillId}; selected from available read-only MCP tools",
        )
    ])
    return state


def _summarize_answer(state: AgentState) -> AgentState:
    request = state["request"]
    skill = state["skill"]
    project_scope = state.get("project_scope", "unknown")
    project_ids = state.get("project_ids", [])
    if not project_ids and not request.toolResults:
        state["response"] = AgentAnswerResponse(
            needClarification=True,
            clarificationQuestion="我还无法判断你要查询哪个项目，请补充项目名称，或说明是否查询全部可访问项目。",
            skillId=skill.skillId,
            projectScope=project_scope,
            projectIds=[],
            answerMetadata={"confidence": "low", "reason": "project_scope_unknown"},
        )
        return state
    if not request.availableTools and not request.toolResults:
        state["response"] = AgentAnswerResponse(
            needClarification=False,
            skillId=skill.skillId,
            projectScope=project_scope,
            projectIds=project_ids,
            answer="当前 MCP 没有返回可用工具，暂时无法完成查询。",
            answerMetadata={"confidence": "low", "reason": "no_available_tools"},
        )
        return state
    if state.get("tool_calls"):
        response_skill_id = PROJECT_REPORT_SKILL_ID if _uses_project_workflow(request, skill) else skill.skillId
        metadata = workflow_metadata(request, "planning") if response_skill_id == PROJECT_REPORT_SKILL_ID else {"confidence": "medium", "phase": "planning"}
        metadata = _with_tool_selection(metadata, state.get("tool_selection"))
        state["response"] = AgentAnswerResponse(
            skillId=response_skill_id,
            projectScope=project_scope,
            projectIds=project_ids,
            toolCalls=state.get("tool_calls", []),
            answerMetadata=metadata,
        )
        return state
    if request.toolResults:
        domain_answer = _domain_summary(request)
        if _uses_project_workflow(request, skill):
            answer = domain_answer or _model_summary(request, skill) or summarize_project_workflow(request, project_scope)
            metadata = workflow_metadata(request, "summary")
            skill_id = PROJECT_REPORT_SKILL_ID
        else:
            # Try domain workflow summarization first, fall back to existing paths
            answer = domain_answer or _model_summary(request, skill) or _deterministic_summary(request, skill, project_scope)
            metadata = _summary_metadata(request, skill)
            skill_id = skill.skillId
        metadata = _with_tool_selection(metadata, state.get("tool_selection"))
        state["response"] = AgentAnswerResponse(
            skillId=skill_id,
            projectScope=project_scope,
            projectIds=project_ids,
            answer=answer,
            answerMetadata=metadata,
        )
        return state
    state["response"] = AgentAnswerResponse(
        skillId=skill.skillId,
        projectScope=project_scope,
        projectIds=project_ids,
        toolCalls=state.get("tool_calls", []),
        answerMetadata={"confidence": "medium", "phase": "planning"},
    )
    return state


def _set_reviewed_tool_plan(state: AgentState, proposed_calls: list[ToolCall]) -> None:
    request = state["request"]
    skill = state["skill"]
    if not proposed_calls:
        state["tool_calls"] = []
        return
    candidates = _score_tool_candidates(request, skill, request.availableTools, proposed_calls)
    review = _model_review_tool_selection(request, candidates, proposed_calls)
    reviewed_calls = _apply_tool_review(proposed_calls, review)
    state["tool_calls"] = reviewed_calls
    state["tool_selection"] = {
        "candidateScores": candidates,
        "modelReview": review,
        "selectedToolNames": [call.toolName for call in reviewed_calls],
    }


def _score_tool_candidates(
    request: AgentAnswerRequest,
    skill: SkillDefinition,
    tools: list[ToolDefinition],
    proposed_calls: list[ToolCall],
) -> list[dict[str, Any]]:
    proposed_names = {call.toolName for call in proposed_calls}
    text = (request.question or "").lower()
    scored: list[dict[str, Any]] = []
    for tool in tools:
        haystack = f"{tool.name} {tool.description or ''}".lower()
        factors: list[str] = []
        score = 0.0
        if tool.name in proposed_names:
            score += 0.45
            factors.append("workflow_proposed")
        if tool_allowed_for_skill(tool.name, skill):
            score += 0.15
            factors.append(f"skill_allowed:{skill.skillId}")
        for keyword, weight in (
            ("质量", 0.18),
            ("缺陷", 0.18),
            ("整改", 0.14),
            ("安全", 0.12),
            ("隐患", 0.12),
            ("进度", 0.12),
            ("上个月", 0.08),
            ("上月", 0.08),
            ("quality", 0.18),
            ("defect", 0.18),
            ("form", 0.08),
        ):
            if keyword in text and keyword.lower() in haystack:
                score += weight
                factors.append(f"keyword:{keyword}")
        if tool.name == "match_form_resource" and any(word in text for word in ("质量", "安全", "进度", "缺陷", "隐患")):
            score += 0.2
            factors.append("domain_form_discovery")
        if tool.name == "query_form_data_list" and request.toolResults:
            score += 0.25
            factors.append("query_after_discovery")
        if tool.name == "batch_get_form_value_detail" and any(item.get("toolName") == "query_form_data_list" for item in request.toolResults):
            score += 0.18
            factors.append("detail_after_list")
        scored.append({
            "toolName": tool.name,
            "description": tool.description or "",
            "score": round(min(score, 1.0), 3),
            "proposed": tool.name in proposed_names,
            "factors": factors,
        })
    return sorted(scored, key=lambda item: (-float(item["score"]), str(item["toolName"])))


def _model_review_tool_selection(
    request: AgentAnswerRequest,
    candidates: list[dict[str, Any]],
    proposed_calls: list[ToolCall],
) -> dict[str, Any]:
    api_key = os.getenv("AGENT_MODEL_API_KEY") or os.getenv("DEEPSEEK_API_KEY")
    if not api_key:
        return {
            "reviewer": "fallback",
            "selectedToolNames": [call.toolName for call in proposed_calls],
            "reasoningSummary": "模型配置不可用，采用确定性相关度评分与工作流候选。",
        }
    try:
        from langchain_openai import ChatOpenAI
    except Exception:
        return {
            "reviewer": "fallback",
            "selectedToolNames": [call.toolName for call in proposed_calls],
            "reasoningSummary": "模型依赖不可用，采用确定性相关度评分与工作流候选。",
        }
    try:
        model = ChatOpenAI(
            api_key=api_key,
            base_url=os.getenv("AGENT_MODEL_BASE_URL", os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
            model=os.getenv("AGENT_MODEL_NAME", os.getenv("DEEPSEEK_MODEL", "deepseek-chat")),
            temperature=0,
        )
        prompt = (
            "你是 MCP 工具选择复核器。只能从 proposedCalls 中保留或剔除工具，不能创造新工具。\n"
            "输出严格 JSON：selectedToolNames(string数组), reasoningSummary(string), rejectedToolNames(string数组)。\n"
            "选择原则：优先保留能获取用户问题所需真实业务数据的工具；发现表单后应查询表单数据；列表数据不足时才查详情。\n"
            f"用户问题：{request.question}\n"
            f"候选评分：{json.dumps(candidates, ensure_ascii=False)}\n"
            f"proposedCalls：{json.dumps([call.model_dump() for call in proposed_calls], ensure_ascii=False)}"
        )
        response = model.invoke(prompt).content
        parsed = json.loads(_extract_json_object(response))
        selected = parsed.get("selectedToolNames")
        if not isinstance(selected, list):
            selected = [call.toolName for call in proposed_calls]
        return {
            "reviewer": "deepseek",
            "selectedToolNames": [str(name) for name in selected],
            "rejectedToolNames": [str(name) for name in parsed.get("rejectedToolNames", []) if name],
            "reasoningSummary": str(parsed.get("reasoningSummary") or "DeepSeek 已复核工具选择。"),
        }
    except Exception as exc:
        return {
            "reviewer": "fallback",
            "selectedToolNames": [call.toolName for call in proposed_calls],
            "reasoningSummary": f"模型复核失败，采用确定性相关度评分。错误：{str(exc)[:160]}",
        }


def _apply_tool_review(proposed_calls: list[ToolCall], review: dict[str, Any]) -> list[ToolCall]:
    selected = {str(name) for name in review.get("selectedToolNames", [])}
    if not selected:
        selected = {call.toolName for call in proposed_calls}
    reviewed: list[ToolCall] = []
    summary = str(review.get("reasoningSummary") or "已完成工具选择复核。")
    for call in proposed_calls:
        if call.toolName not in selected:
            continue
        reason = call.reason or ""
        call.reason = f"{reason}；DeepSeek复核：{summary}" if reason else f"DeepSeek复核：{summary}"
        reviewed.append(call)
    return reviewed or proposed_calls


def _with_tool_selection(metadata: dict[str, Any], selection: dict[str, Any] | None) -> dict[str, Any]:
    if selection:
        metadata = dict(metadata)
        metadata["toolSelection"] = selection
    return metadata


def _extract_json_object(content: str) -> str:
    text = content.strip()
    if text.startswith("{") and text.endswith("}"):
        return text
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start:end + 1]
    raise ValueError("model response does not contain JSON object")


def _uses_project_workflow(request: AgentAnswerRequest, skill: SkillDefinition) -> bool:
    return skill.skillId in (PROJECT_REPORT_SKILL_ID, "weekly_report", "project_status_qa") and is_project_report_question(request.question)


def _quality_check(state: AgentState) -> AgentState:
    response = state["response"]
    if response.answer and "数据范围" not in response.answer:
        response.answer = response.answer.rstrip() + "\n\n数据范围：基于本次 MCP 工具返回结果。"
    state["response"] = response
    return state


def _project_matches_question(project_id: str, project_name: str | None, text: str) -> bool:
    candidates = [project_id or "", project_name or ""]
    return any(candidate and candidate.lower() in text for candidate in candidates)


def _select_tool(question: str, skill: SkillDefinition, tools: list[ToolDefinition]) -> ToolDefinition | None:
    candidates = [tool for tool in tools if _is_read_only(tool.name) and tool_allowed_for_skill(tool.name, skill)]
    if not candidates:
        candidates = [tool for tool in tools if _is_read_only(tool.name)]
    if not candidates:
        return None
    text = (question or "").lower()
    scored: list[tuple[int, ToolDefinition]] = []
    for tool in candidates:
        haystack = f"{tool.name} {tool.description or ''}".lower()
        score = 0
        for keyword in ("风险", "逾期", "待办", "任务", "risk", "overdue", "task"):
            if keyword in text and keyword in haystack:
                score += 2
        for keyword in ("状态", "进度", "健康", "status", "progress", "health"):
            if keyword in text and keyword in haystack:
                score += 2
        for keyword in ("周报", "日报", "总结", "report", "summary"):
            if keyword in text and keyword in haystack:
                score += 2
        scored.append((score, tool))
    return max(scored, key=lambda item: item[0])[1]


def _is_read_only(tool_name: str) -> bool:
    name = tool_name or ""
    return name.startswith(READ_ONLY_PREFIXES) or ".query_" in name or ".get_" in name or ".list_" in name


def _build_arguments(question: str, tool: ToolDefinition) -> dict[str, Any]:
    arguments: dict[str, Any] = {}
    properties = ((tool.inputSchema or {}).get("properties") or {})
    for key in ("question", "query", "prompt", "input", "message"):
        if key in properties:
            arguments[key] = question
    if not arguments and properties:
        first_string_key = next(
            (
                key
                for key, schema in properties.items()
                if isinstance(schema, dict) and schema.get("type") in ("string", None)
            ),
            None,
        )
        if first_string_key:
            arguments[first_string_key] = question
    return arguments


def _model_summary(request: AgentAnswerRequest, skill: SkillDefinition) -> str | None:
    api_key = os.getenv("AGENT_MODEL_API_KEY")
    if not api_key:
        return None
    try:
        from langchain_openai import ChatOpenAI
    except Exception:
        return None
    try:
        model = ChatOpenAI(
            api_key=api_key,
            base_url=os.getenv("AGENT_MODEL_BASE_URL", "https://api.deepseek.com"),
            model=os.getenv("AGENT_MODEL_NAME", "deepseek-chat"),
            temperature=0.1,
        )
        prompt = (
            f"{skill.systemPrompt}\n"
            f"回答模板：{skill.answerTemplate}\n"
            "必须说明数据范围和失败项目。不要编造 MCP 未返回的数据。\n"
            f"用户问题：{request.question}\n"
            f"MCP 结果：{json.dumps(request.toolResults, ensure_ascii=False)}"
        )
        return model.invoke(prompt).content
    except Exception:
        return None


def _deterministic_summary(request: AgentAnswerRequest, skill: SkillDefinition, project_scope: str) -> str:
    succeeded = [item for item in request.toolResults if item.get("status") == "SUCCEEDED"]
    failed = [item for item in request.toolResults if item.get("status") != "SUCCEEDED"]
    lines = [
        "结论：已基于 Primelayer MCP 返回数据完成查询。",
        "",
        f"业务技能：{skill.name}",
        f"成功项目数：{len(succeeded)}",
        f"失败项目数：{len(failed)}",
    ]
    if succeeded:
        lines.append("")
        lines.append("关键数据：")
        for item in succeeded[:5]:
            project_name = item.get("projectName") or item.get("projectId") or "-"
            result = item.get("result")
            lines.append(f"- {project_name}: {_compact_value(result)}")
    if failed:
        lines.append("")
        lines.append("失败项目：")
        for item in failed:
            project_name = item.get("projectName") or item.get("projectId") or "-"
            lines.append(f"- {project_name}: {item.get('error') or '工具调用失败'}")
    lines.append("")
    lines.append(f"数据范围：{project_scope}；仅基于本次 MCP 工具返回结果。")
    return "\n".join(lines)


def _compact_value(value: Any) -> str:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return text if len(text) <= 260 else text[:260] + "..."


def _summary_metadata(request: AgentAnswerRequest, skill: SkillDefinition) -> dict[str, Any]:
    failed = [item for item in request.toolResults if item.get("status") != "SUCCEEDED"]
    return {
        "confidence": "medium" if failed else "high",
        "skillName": skill.name,
        "toolResultCount": len(request.toolResults),
        "failedProjects": [item.get("projectId") for item in failed],
    }


# ---------------------------------------------------------------------------
# Domain workflow helpers
# ---------------------------------------------------------------------------


def _result_is_success(item: dict[str, Any]) -> bool:
    """Check if a tool result item has a succeeded status."""
    return str(item.get("status", "")).upper() == "SUCCEEDED"


def _extract_domain_data(tool_results: list[dict[str, Any]]) -> dict[str, Any]:
    """Extract a merged {total, items} dict from query_form_data_list results."""
    merged_items: list[dict[str, Any]] = []
    merged_total = 0
    for item in tool_results:
        if item.get("toolName") not in ("query_form_data_list", "batch_get_form_value_detail"):
            continue
        if not _result_is_success(item):
            continue
        item_total = 0
        item_records: list[dict[str, Any]] = []
        for node in _walk_payload(item.get("result", {})):
            if not isinstance(node, dict):
                continue
            for total_key in ("total", "totalCount"):
                try:
                    item_total = max(item_total, int(node.get(total_key) or 0))
                except Exception:
                    pass
            for records_key in ("items", "records", "datas", "list", "data"):
                records = node.get(records_key)
                if isinstance(records, list):
                    item_records.extend(_normalize_business_record(record) for record in records if isinstance(record, dict))
        merged_items.extend(item_records)
        merged_total += item_total if item_total else len(item_records)
    return {"total": merged_total, "items": merged_items}


def _normalize_business_record(record: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(record)
    for field_list_name in ("detailList", "formValues", "formValueList", "values", "fieldValues"):
        field_list = record.get(field_list_name)
        if not isinstance(field_list, list):
            continue
        for field in field_list:
            if not isinstance(field, dict):
                continue
            name = str(
                field.get("fieldName")
                or field.get("name")
                or field.get("title")
                or field.get("label")
                or field.get("fieldTitle")
                or ""
            )
            value = field.get("fieldValue")
            if value is None:
                value = field.get("value")
            if value is None:
                value = field.get("displayValue")
            if value is None:
                value = field.get("text")
            _put_canonical_field(normalized, name, value)
    return normalized


def _put_canonical_field(target: dict[str, Any], name: str, value: Any) -> None:
    if value is None or value == "":
        return
    if any(key in name for key in ("整改状态", "状态", "闭环状态")):
        target.setdefault("status", str(value))
    elif any(key in name for key in ("严重程度", "严重度", "风险等级", "优先级")):
        target.setdefault("severity", str(value))
        target.setdefault("riskLevel", str(value))
    elif any(key in name for key in ("责任人", "负责人", "整改人")):
        target.setdefault("responsiblePerson", str(value))
    elif any(key in name for key in ("区域", "位置", "楼层", "标段")):
        target.setdefault("area", str(value))
    elif any(key in name for key in ("问题类型", "缺陷类型", "类型", "分类")):
        target.setdefault("type", str(value))
    elif any(key in name for key in ("问题描述", "缺陷描述", "描述", "内容")):
        target.setdefault("description", str(value))


def _walk_payload(value: Any) -> list[Any]:
    items = [value]
    if isinstance(value, dict):
        for child in value.values():
            items.extend(_walk_payload(child))
    elif isinstance(value, list):
        for child in value:
            items.extend(_walk_payload(child))
    elif isinstance(value, str):
        stripped = value.strip()
        if stripped.startswith("{") or stripped.startswith("["):
            try:
                items.extend(_walk_payload(json.loads(stripped)))
            except Exception:
                pass
    return items


def _domain_summary(request: AgentAnswerRequest) -> str | None:
    """Build a domain-specific management report from tool results.

    Returns None if the domain cannot be detected or no domain data is
    available, so callers can fall back to other summarization paths.
    """
    try:
        config = get_domain_config()
        intent = detect_domain_intent(request.question, config)
    except Exception:
        return None

    if "general" in intent.domains:
        return None

    # Resolve time range for the detected domain
    try:
        tr = resolve_time_range(request.question, default_domain=intent.domains[0])
        intent.timeRange = tr
    except Exception:
        pass  # Time range resolution is best-effort

    # Extract and merge data from query_form_data_list results
    data = _extract_domain_data(request.toolResults)
    if not data.get("items"):
        return None  # No structured data to summarize

    domain = intent.domains[0]

    # Compute domain-specific metrics
    compute_fn = {
        "quality": compute_quality_metrics,
        "safety": compute_safety_metrics,
        "progress": compute_progress_metrics,
        "risk": compute_risk_metrics,
    }.get(domain)
    if compute_fn is None:
        return None
    try:
        metrics = compute_fn(data)
    except Exception:
        return None

    # Build data source descriptions
    succeeded = [item for item in request.toolResults if _result_is_success(item)]
    failed = [item for item in request.toolResults if not _result_is_success(item)]
    data_sources = [
        str(item.get("formName") or item.get("toolName") or "")
        for item in succeeded
        if item.get("formName") or item.get("toolName")
    ]
    failed_sources = [
        str(item.get("formName") or item.get("toolName") or "")
        for item in failed
        if item.get("formName") or item.get("toolName")
    ]

    try:
        report = build_management_report(
            domain=domain,
            metrics=metrics,
            data_sources=data_sources,
            failed_sources=failed_sources,
            domain_intent=intent,
        )
        return _model_refine_management_report(
            question=request.question,
            domain=domain,
            metrics=metrics,
            data_sources=data_sources,
            failed_sources=failed_sources,
            deterministic_report=report,
            intent=intent,
        ) or report
    except Exception:
        return None


def _model_refine_management_report(
    question: str,
    domain: str,
    metrics: dict[str, Any],
    data_sources: list[str],
    failed_sources: list[str],
    deterministic_report: str,
    intent: Any,
) -> str | None:
    api_key = os.getenv("AGENT_MODEL_API_KEY") or os.getenv("DEEPSEEK_API_KEY")
    if not api_key:
        return None
    try:
        from langchain_openai import ChatOpenAI
    except Exception:
        return None
    try:
        model = ChatOpenAI(
            api_key=api_key,
            base_url=os.getenv("AGENT_MODEL_BASE_URL", os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
            model=os.getenv("AGENT_MODEL_NAME", os.getenv("DEEPSEEK_MODEL", "deepseek-chat")),
            temperature=0.1,
        )
        prompt = (
            "你是 Primelayer 项目数据分析师。请基于已计算好的指标写管理层可读报告。\n"
            "硬性规则：\n"
            "1. 禁止输出原始 JSON、toolName、content、detailList、formId、dataId 等内部字段。\n"
            "2. 不得编造指标；所有数字只能来自 metrics。\n"
            "3. 必须包含：结论、关键指标、问题分布、趋势与风险、建议动作、数据范围。\n"
            "4. 如果某项分布为空，要说明数据字段不足，不要臆测。\n"
            "5. 中文回答，简洁，适合飞书卡片阅读。\n"
            f"用户问题：{question}\n"
            f"领域：{domain}\n"
            f"时间范围：{intent.timeRange.model_dump() if getattr(intent, 'timeRange', None) else None}\n"
            f"metrics：{json.dumps(metrics, ensure_ascii=False)}\n"
            f"数据源：{json.dumps(data_sources, ensure_ascii=False)}\n"
            f"失败数据源：{json.dumps(failed_sources, ensure_ascii=False)}\n"
            f"确定性草稿：{deterministic_report}"
        )
        answer = str(model.invoke(prompt).content)
        forbidden = ("toolName", "content", "detailList", "formId", "dataId", "{", "}")
        if any(token in answer for token in forbidden):
            return None
        return answer
    except Exception:
        return None
