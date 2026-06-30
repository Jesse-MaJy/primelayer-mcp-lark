from __future__ import annotations

from dataclasses import dataclass

from .models import ToolDefinition


@dataclass(frozen=True)
class ToolInfo:
    name: str
    category: str
    description: str | None = None


def catalog_tools(tools: list[ToolDefinition]) -> dict[str, ToolInfo]:
    return {
        tool.name: ToolInfo(
            name=tool.name,
            category=categorize_tool(tool.name),
            description=tool.description,
        )
        for tool in tools
    }


def categorize_tool(name: str) -> str:
    if name in ("get_base_form_info", "get_account_info", "get_organization_info"):
        return "base_context"
    if name in ("match_form_resource", "list_form_resource", "get_form_definition"):
        return "form_resource"
    if name in ("query_form_data_list", "batch_get_form_value_detail", "query_todo_list", "get_process_approval_info"):
        return "form_data"
    if name in ("get_report", "get_async_task_result"):
        return "report"
    if name in ("get_folder_tree", "get_picture_folders"):
        return "file_tree"
    if name in ("get_file_by_name", "get_file_list", "get_file_detail", "get_file_reference", "get_picture_list", "get_picture_detail"):
        return "file_data"
    if name.startswith("get_bim_"):
        return "bim"
    if name.startswith(("get_", "list_", "query_", "search_")):
        return "read_only"
    return "other"


def has_tool(tools: list[ToolDefinition], name: str) -> bool:
    return any(tool.name == name for tool in tools)
