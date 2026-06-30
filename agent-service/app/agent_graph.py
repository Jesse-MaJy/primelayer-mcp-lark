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

READ_ONLY_PREFIXES = ("get_", "list_", "query_", "search_", "primelayer.query_")


class AgentState(TypedDict, total=False):
    request: AgentAnswerRequest
    skill: SkillDefinition
    project_scope: str
    project_ids: list[str]
    tool_calls: list[ToolCall]
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
        state["tool_calls"] = calls
        return state
    if request.toolResults:
        state["tool_calls"] = []
        return state
    tool = _select_tool(request.question, skill, request.availableTools)
    if tool is None:
        state["tool_calls"] = []
        return state
    state["tool_calls"] = [
        ToolCall(
            toolName=tool.name,
            arguments=_build_arguments(request.question, tool),
            projectIds=project_ids,
            reason=f"skill={skill.skillId}; selected from available read-only MCP tools",
        )
    ]
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
        state["response"] = AgentAnswerResponse(
            skillId=response_skill_id,
            projectScope=project_scope,
            projectIds=project_ids,
            toolCalls=state.get("tool_calls", []),
            answerMetadata=workflow_metadata(request, "planning") if response_skill_id == PROJECT_REPORT_SKILL_ID else {"confidence": "medium", "phase": "planning"},
        )
        return state
    if request.toolResults:
        if _uses_project_workflow(request, skill):
            answer = _model_summary(request, skill) or summarize_project_workflow(request, project_scope)
            metadata = workflow_metadata(request, "summary")
            skill_id = PROJECT_REPORT_SKILL_ID
        else:
            answer = _model_summary(request, skill) or _deterministic_summary(request, skill, project_scope)
            metadata = _summary_metadata(request, skill)
            skill_id = skill.skillId
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
