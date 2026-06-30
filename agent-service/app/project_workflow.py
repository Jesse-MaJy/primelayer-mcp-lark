from __future__ import annotations

import json
import re
from datetime import date, timedelta
from typing import Any

from .models import AgentAnswerRequest, ToolCall, ToolDefinition
from .tool_catalog import has_tool

PROJECT_REPORT_SKILL_ID = "project_report"

FORM_KEYWORDS = ("施工日报", "日报", "周报", "质量", "安全", "隐患", "检查", "进度")
REPORT_KEYWORDS = ("日报", "周报", "月报", "季报")


def is_project_report_question(question: str) -> bool:
    text = question or ""
    return any(keyword in text for keyword in ("施工", "今日", "今天", "日报", "周报", "质量", "安全", "隐患", "检查", "进度", "项目情况"))


def plan_project_workflow(request: AgentAnswerRequest, project_ids: list[str]) -> list[ToolCall]:
    if not project_ids:
        return []
    if not request.toolResults:
        return _initial_calls(request, project_ids)

    async_calls = _async_report_calls(request, project_ids)
    if async_calls:
        return async_calls

    form_data_calls = _form_data_calls(request, project_ids)
    if form_data_calls:
        return form_data_calls

    detail_calls = _detail_calls(request, project_ids)
    if detail_calls:
        return detail_calls

    return []


def summarize_project_workflow(request: AgentAnswerRequest, project_scope: str) -> str:
    succeeded = [item for item in request.toolResults if _is_success(item)]
    failed = [item for item in request.toolResults if not _is_success(item)]
    lines = [
        "结论：已基于 Primelayer 项目数据完成查询；以下内容仅来自本次可访问的数据。",
        "",
        "项目背景：",
        _project_background(request, succeeded),
        "",
        "施工进展：",
        _section_summary(succeeded, ("施工", "日报", "进度", "report"), "本次没有从 MCP 返回中提取到明确的施工进展记录。"),
        "",
        "质量安全：",
        _section_summary(succeeded, ("质量", "安全", "隐患", "检查"), "本次没有从 MCP 返回中提取到明确的质量或安全记录。"),
        "",
        "风险与建议：",
        _risk_summary(succeeded, failed),
        "",
        f"数据范围：{project_scope}；共使用 {len(succeeded)} 条成功工具结果，{len(failed)} 条失败工具结果。",
    ]
    if failed:
        lines.extend(["", "未完成的数据源："])
        for item in failed:
            project_name = item.get("projectName") or item.get("projectId") or "-"
            tool_name = item.get("toolName") or "MCP 工具"
            lines.append(f"- {project_name} / {tool_name}：暂时无法读取该数据源。")
    return "\n".join(lines)


def workflow_metadata(request: AgentAnswerRequest, phase: str, round_number: int = 1) -> dict[str, Any]:
    failed = [item for item in request.toolResults if not _is_success(item)]
    return {
        "phase": phase,
        "workflow": PROJECT_REPORT_SKILL_ID,
        "round": round_number,
        "dataSources": sorted({str(item.get("toolName")) for item in request.toolResults if item.get("toolName")}),
        "failedToolCount": len(failed),
    }


def _initial_calls(request: AgentAnswerRequest, project_ids: list[str]) -> list[ToolCall]:
    calls: list[ToolCall] = []
    if has_tool(request.availableTools, "get_base_form_info"):
        calls.append(ToolCall(toolName="get_base_form_info", arguments={}, projectIds=project_ids, reason="初始化项目和表单上下文"))

    if has_tool(request.availableTools, "match_form_resource"):
        for keyword in _keywords_for_question(request.question):
            calls.append(
                ToolCall(
                    toolName="match_form_resource",
                    arguments={"name": keyword},
                    projectIds=project_ids,
                    reason="匹配项目报告相关表单资源",
                )
            )

    if _wants_report(request.question) and has_tool(request.availableTools, "get_report"):
        start, end = _time_range(request.question)
        calls.append(
            ToolCall(
                toolName="get_report",
                arguments={"startTime": start, "endTime": end, "reportType": _report_type(request.question)},
                projectIds=project_ids,
                reason="查询 Primelayer 报告异步任务",
            )
        )
    return calls


def _async_report_calls(request: AgentAnswerRequest, project_ids: list[str]) -> list[ToolCall]:
    if not has_tool(request.availableTools, "get_async_task_result"):
        return []
    polled_task_ids = {
        str((item.get("arguments") or {}).get("taskId"))
        for item in request.toolResults
        if item.get("toolName") == "get_async_task_result"
    }
    calls: list[ToolCall] = []
    for item in request.toolResults:
        if item.get("toolName") != "get_report" or not _is_success(item):
            continue
        task_id = _first_value(item.get("result"), ("taskId", "task_id"))
        if task_id and str(task_id) not in polled_task_ids:
            calls.append(
                ToolCall(
                    toolName="get_async_task_result",
                    arguments={"taskId": str(task_id)},
                    projectIds=_item_project_ids(item, project_ids),
                    reason="轮询异步报告结果",
                )
            )
    return calls


def _form_data_calls(request: AgentAnswerRequest, project_ids: list[str]) -> list[ToolCall]:
    if not has_tool(request.availableTools, "query_form_data_list"):
        return []
    queried_form_ids = {
        str((item.get("arguments") or {}).get("formId"))
        for item in request.toolResults
        if item.get("toolName") == "query_form_data_list"
    }
    start, end = _time_range(request.question)
    calls: list[ToolCall] = []
    for item in request.toolResults:
        if item.get("toolName") not in ("match_form_resource", "list_form_resource") or not _is_success(item):
            continue
        for form in _extract_forms(item.get("result")):
            form_id = str(form.get("formId") or form.get("id") or "")
            if not form_id or form_id in queried_form_ids:
                continue
            calls.append(
                ToolCall(
                    toolName="query_form_data_list",
                    arguments={
                        "formId": form_id,
                        "page": 1,
                        "pageSize": 20,
                        "filter": {"createTime": [start, end]},
                    },
                    projectIds=_item_project_ids(item, project_ids),
                    reason="查询匹配表单在目标时间范围内的数据",
                )
            )
    return calls


def _detail_calls(request: AgentAnswerRequest, project_ids: list[str]) -> list[ToolCall]:
    if not has_tool(request.availableTools, "batch_get_form_value_detail"):
        return []
    detailed_pairs = {
        (str((item.get("arguments") or {}).get("formId")), tuple((item.get("arguments") or {}).get("dataIdList") or []))
        for item in request.toolResults
        if item.get("toolName") == "batch_get_form_value_detail"
    }
    calls: list[ToolCall] = []
    for item in request.toolResults:
        if item.get("toolName") != "query_form_data_list" or not _is_success(item):
            continue
        form_id = str((item.get("arguments") or {}).get("formId") or _first_value(item.get("result"), ("formId",)))
        data_ids = [str(value) for value in _extract_values(item.get("result"), ("dataId", "data_id", "id"))[:20] if value]
        pair = (form_id, tuple(data_ids))
        if form_id and data_ids and pair not in detailed_pairs:
            calls.append(
                ToolCall(
                    toolName="batch_get_form_value_detail",
                    arguments={"formId": form_id, "dataIdList": data_ids},
                    projectIds=_item_project_ids(item, project_ids),
                    reason="获取表单记录详情用于项目报告汇总",
                )
            )
    return calls


def _keywords_for_question(question: str) -> list[str]:
    text = question or ""
    if "周报" in text:
        return ["周报", "施工日报", "质量", "安全"]
    if "日报" in text or "施工" in text or "今日" in text or "今天" in text:
        return ["施工日报", "质量", "安全", "进度"]
    matched = [keyword for keyword in FORM_KEYWORDS if keyword in text]
    return matched or ["施工日报", "质量", "安全", "进度"]


def _wants_report(question: str) -> bool:
    return any(keyword in (question or "") for keyword in REPORT_KEYWORDS)


def _report_type(question: str) -> str:
    text = question or ""
    if "周报" in text:
        return "WEEKLY"
    if "月报" in text:
        return "MONTHLY"
    if "季报" in text:
        return "QUARTERLY"
    return "DAILY"


def _time_range(question: str) -> tuple[str, str]:
    today = date.today()
    text = question or ""
    if "本周" in text or "周报" in text:
        start_day = today - timedelta(days=today.weekday())
        end_day = start_day + timedelta(days=6)
    elif "本月" in text or "月报" in text:
        start_day = today.replace(day=1)
        next_month = today.replace(year=today.year + 1, month=1, day=1) if today.month == 12 else today.replace(month=today.month + 1, day=1)
        end_day = next_month - timedelta(days=1)
    else:
        start_day = today
        end_day = today
    return f"{start_day.isoformat()} 00:00:00", f"{end_day.isoformat()} 23:59:59"


def _extract_forms(value: Any) -> list[dict[str, Any]]:
    forms: list[dict[str, Any]] = []
    for item in _walk(value):
        if not isinstance(item, dict):
            continue
        has_form_id = any(key in item for key in ("formId", "form_id", "id"))
        has_form_name = any(key in item for key in ("formTitle", "title", "name"))
        if has_form_id and has_form_name:
            forms.append(item)
    return forms


def _extract_values(value: Any, keys: tuple[str, ...]) -> list[Any]:
    values: list[Any] = []
    for item in _walk(value):
        if isinstance(item, dict):
            for key in keys:
                if key in item and item[key]:
                    values.append(item[key])
                    break
    return values


def _first_value(value: Any, keys: tuple[str, ...]) -> Any:
    values = _extract_values(value, keys)
    return values[0] if values else None


def _walk(value: Any) -> list[Any]:
    items = [value]
    if isinstance(value, dict):
        for child in value.values():
            items.extend(_walk(child))
    elif isinstance(value, list):
        for child in value:
            items.extend(_walk(child))
    return items


def _project_background(request: AgentAnswerRequest, succeeded: list[dict[str, Any]]) -> str:
    names = [project.projectName or project.projectId for project in request.projects]
    account_info = _section_summary(succeeded, ("工作空间", "项目", "租户", "base_form"), "")
    if account_info:
        return account_info
    return "、".join(names) if names else "当前项目"


def _section_summary(succeeded: list[dict[str, Any]], keywords: tuple[str, ...], empty_text: str) -> str:
    snippets: list[str] = []
    for item in succeeded:
        text = _compact_value({"toolName": item.get("toolName"), "result": item.get("result")})
        if any(keyword.lower() in text.lower() for keyword in keywords):
            snippets.append(f"- {item.get('projectName') or item.get('projectId') or '-'} / {item.get('toolName')}: {text}")
    return "\n".join(snippets[:6]) if snippets else empty_text


def _risk_summary(succeeded: list[dict[str, Any]], failed: list[dict[str, Any]]) -> str:
    if failed and not succeeded:
        return "暂时无法读取项目数据，请稍后重试或联系管理员查看后台审计日志。"
    if failed:
        return "部分数据源暂时无法读取；建议先基于已返回数据推进现场核对，并由管理员查看后台审计日志定位失败工具。"
    return "未发现工具调用失败；建议结合现场负责人确认关键质量、安全和进度事项。"


def _compact_value(value: Any) -> str:
    text = json.dumps(value, ensure_ascii=False, default=str)
    text = _sanitize_raw_error(text)
    return text if len(text) <= 360 else text[:360] + "..."


def _sanitize_raw_error(text: str) -> str:
    sanitized = re.sub(r"\{[^{}]*(jsonrpc|nodeCodeList|error)[^{}]*\}", "底层数据源返回错误", text, flags=re.IGNORECASE)
    sanitized = sanitized.replace("nodeCodeList", "必要查询范围")
    sanitized = sanitized.replace("jsonrpc", "数据源协议")
    return sanitized


def _is_success(item: dict[str, Any]) -> bool:
    return str(item.get("status", "")).upper() == "SUCCEEDED"


def _item_project_ids(item: dict[str, Any], default_project_ids: list[str]) -> list[str]:
    project_id = item.get("projectId")
    return [str(project_id)] if project_id else default_project_ids
