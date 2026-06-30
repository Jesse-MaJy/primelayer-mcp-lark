from app.agent_graph import run_agent
from app.models import AgentAnswerRequest, ProjectRef, ToolDefinition
from app.skills import classify_skill


def test_skill_routing_task_risk():
    assert classify_skill("帮我看一下项目逾期待办和风险").skillId == "task_risk_qa"


def test_project_clarification_when_multiple_projects_without_hint():
    response = run_agent(
        AgentAnswerRequest(
            requestId="test",
            question="帮我看一下风险",
            projects=[
                ProjectRef(projectId="a", projectName="A 项目"),
                ProjectRef(projectId="b", projectName="B 项目"),
            ],
            availableTools=[ToolDefinition(name="query_tasks", description="查询任务风险")],
        )
    )
    assert response.needClarification is True


def test_tool_plan_filters_to_read_only_tools():
    response = run_agent(
        AgentAnswerRequest(
            requestId="test",
            question="帮我看一下 A 项目逾期任务",
            projects=[ProjectRef(projectId="a", projectName="A 项目")],
            availableTools=[
                ToolDefinition(name="delete_task", description="删除任务"),
                ToolDefinition(name="query_tasks", description="查询任务风险"),
            ],
        )
    )
    assert response.needClarification is False
    assert response.toolCalls[0].toolName == "query_tasks"


def test_project_construction_question_starts_business_workflow_without_file_lookup():
    response = run_agent(
        AgentAnswerRequest(
            requestId="test",
            question="Roche今日施工情况",
            projects=[ProjectRef(projectId="Roche", projectName="Roche")],
            availableTools=[
                ToolDefinition(name="get_file_by_name", description="通过文件名称和节点数组模糊查询文件"),
                ToolDefinition(name="get_base_form_info", description="获取当前项目和用户信息"),
                ToolDefinition(name="match_form_resource", description="根据名称模糊匹配表单资源"),
                ToolDefinition(name="query_form_data_list", description="分页获取普通表单、流程表单的数据列表"),
            ],
        )
    )

    planned_tools = [call.toolName for call in response.toolCalls]
    assert response.skillId == "project_report"
    assert "get_base_form_info" in planned_tools
    assert "match_form_resource" in planned_tools
    assert "get_file_by_name" not in planned_tools


def test_project_workflow_queries_form_data_after_matching_daily_form():
    response = run_agent(
        AgentAnswerRequest(
            requestId="test",
            question="Roche今日施工情况",
            projects=[ProjectRef(projectId="Roche", projectName="Roche")],
            availableTools=[
                ToolDefinition(name="query_form_data_list", description="分页获取普通表单、流程表单的数据列表"),
                ToolDefinition(name="batch_get_form_value_detail", description="根据表单 id 和数据 id 获取表单数据详情"),
            ],
            toolResults=[
                {
                    "projectId": "Roche",
                    "projectName": "Roche",
                    "toolName": "get_base_form_info",
                    "status": "SUCCEEDED",
                    "result": {"projectName": "Roche"},
                },
                {
                    "projectId": "Roche",
                    "projectName": "Roche",
                    "toolName": "match_form_resource",
                    "status": "SUCCEEDED",
                    "arguments": {"name": "施工日报"},
                    "result": {"resources": [{"formId": "daily_form", "formTitle": "施工日报"}]},
                },
            ],
        )
    )

    assert response.toolCalls[0].toolName == "query_form_data_list"
    assert response.toolCalls[0].arguments["formId"] == "daily_form"
    assert response.toolCalls[0].arguments["filter"]["createTime"][0].endswith("00:00:00")
    assert response.toolCalls[0].arguments["filter"]["createTime"][1].endswith("23:59:59")


def test_project_workflow_polls_async_report_result():
    response = run_agent(
        AgentAnswerRequest(
            requestId="test",
            question="Roche本周周报",
            projects=[ProjectRef(projectId="Roche", projectName="Roche")],
            availableTools=[ToolDefinition(name="get_async_task_result", description="异步任务结果查询接口")],
            toolResults=[
                {
                    "projectId": "Roche",
                    "projectName": "Roche",
                    "toolName": "get_report",
                    "status": "SUCCEEDED",
                    "result": {"taskId": "task-123"},
                },
            ],
        )
    )

    assert response.toolCalls[0].toolName == "get_async_task_result"
    assert response.toolCalls[0].arguments == {"taskId": "task-123"}


def test_failed_tool_summary_hides_raw_mcp_errors():
    response = run_agent(
        AgentAnswerRequest(
            requestId="test",
            question="Roche今日施工情况",
            projects=[ProjectRef(projectId="Roche", projectName="Roche")],
            availableTools=[],
            toolResults=[
                {
                    "projectId": "Roche",
                    "projectName": "Roche",
                    "toolName": "get_file_by_name",
                    "status": "FAILED",
                    "error": """{"jsonrpc":"2.0","error":"参数 'nodeCodeList' 数组元素不能为空"}""",
                }
            ],
        )
    )

    assert response.answer is not None
    assert "nodeCodeList" not in response.answer
    assert "jsonrpc" not in response.answer
    assert "MCP 配置" not in response.answer
