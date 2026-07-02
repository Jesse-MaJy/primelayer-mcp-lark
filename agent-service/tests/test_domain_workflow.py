from app.models import ToolCall


def test_tool_call_with_pagination_and_purpose():
    """ToolCall constructed with pagination and purpose should store those values."""
    call = ToolCall(
        toolName="query_tasks",
        arguments={"projectId": "abc"},
        pagination={"page": 1, "pageSize": 20},
        purpose="fetching_overdue_tasks",
    )
    assert call.toolName == "query_tasks"
    assert call.pagination == {"page": 1, "pageSize": 20}
    assert call.purpose == "fetching_overdue_tasks"


def test_tool_call_without_pagination_and_purpose_defaults_to_none():
    """ToolCall constructed without pagination and purpose should default to None (backward compat)."""
    call = ToolCall(
        toolName="query_tasks",
        arguments={"projectId": "abc"},
    )
    assert call.toolName == "query_tasks"
    assert call.pagination is None
    assert call.purpose is None
