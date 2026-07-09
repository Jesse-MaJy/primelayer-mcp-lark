from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class SkillDefinition:
    skillId: str
    name: str
    description: str
    triggerExamples: tuple[str, ...]
    allowedTools: tuple[str, ...]
    systemPrompt: str
    requiredContext: tuple[str, ...]
    answerTemplate: str


SKILLS: tuple[SkillDefinition, ...] = (
    SkillDefinition(
        skillId="project_report",
        name="项目报告与施工情况",
        description="回答项目背景、施工日报、周报、质量、安全、隐患、检查和进度情况。",
        triggerExamples=("今日施工", "今天施工", "施工情况", "施工日报", "质量安全", "安全隐患", "项目情况","质量", "安全", "隐患", "检查",),
        allowedTools=(
            "get_base_form_info",
            "match_form_resource",
            "list_form_resource",
            "query_form_data_list",
            "batch_get_form_value_detail",
            "get_report",
            "get_async_task_result",
            "query_todo_list",
        ),
        systemPrompt="你是项目报告助手，只基于 Primelayer MCP 返回的数据回答施工、质量、安全和进度问题。",
        requiredContext=("project",),
        answerTemplate="先说明项目背景，再输出施工进展、质量安全、风险建议和数据范围。",
    ),
    SkillDefinition(
        skillId="project_status_qa",
        name="项目状态问答",
        description="回答项目状态、进度、当前情况、健康度和整体风险。",
        triggerExamples=("项目怎么样", "当前进度", "健康度", "项目状态", "目前情况"),
        allowedTools=(
            "primelayer.query_project_health",
            "query_project_health",
            "get_project_health",
            "get_project_status",
            "get_account_info",
        ),
        systemPrompt="你是项目状态分析助手，只基于工具返回的数据回答。",
        requiredContext=("project",),
        answerTemplate="先给结论，再列出状态、风险、建议动作和数据范围。",
    ),
    SkillDefinition(
        skillId="task_risk_qa",
        name="任务风险问答",
        description="回答逾期任务、风险、负责人、优先级、阻塞项和趋势。",
        triggerExamples=("逾期", "风险", "待办", "负责人", "优先级", "阻塞"),
        allowedTools=(
            "primelayer.query_tasks",
            "query_tasks",
            "search_tasks",
            "list_tasks",
            "get_task_risks",
            "get_project_risks",
        ),
        systemPrompt="你是项目任务风险分析助手，只基于工具返回的数据回答。",
        requiredContext=("project",),
        answerTemplate="按风险等级和负责人组织答案，并标明失败项目。",
    ),
    SkillDefinition(
        skillId="weekly_report",
        name="日报周报生成",
        description="生成日报、周报、阶段总结、进展摘要和下周计划。",
        triggerExamples=("周报", "日报", "总结", "本周", "下周", "阶段汇报"),
        allowedTools=(
            "primelayer.query_project_health",
            "primelayer.query_tasks",
            "query_project_health",
            "query_tasks",
            "get_weekly_report",
            "generate_report",
        ),
        systemPrompt="你是项目报告助手，只基于工具返回的数据生成报告。",
        requiredContext=("project",),
        answerTemplate="输出进展、风险、阻塞、下阶段计划和数据范围。",
    ),
    SkillDefinition(
        skillId="general_mcp_qa",
        name="通用 MCP 问答",
        description="兜底处理只读 MCP 查询。",
        triggerExamples=("查询", "看看", "帮我分析"),
        allowedTools=("get_", "list_", "query_", "search_", "primelayer.query_"),
        systemPrompt="你是通用 MCP 问答助手，只使用只读工具。",
        requiredContext=("project",),
        answerTemplate="直接回答问题，并说明数据范围。",
    ),
)


def classify_skill(question: str) -> SkillDefinition:
    text = (question or "").lower()
    score_by_skill: dict[SkillDefinition, int] = {}
    for skill in SKILLS:
        score = 0
        for example in skill.triggerExamples:
            if example.lower() in text:
                score += 2
        if skill.skillId == "project_status_qa" and any(word in text for word in ("health", "status", "progress")):
            score += 1
        if skill.skillId == "task_risk_qa" and any(word in text for word in ("risk", "task", "overdue", "owner")):
            score += 1
        if skill.skillId == "weekly_report" and any(word in text for word in ("weekly", "daily", "report", "summary")):
            score += 1
        if skill.skillId == "project_report" and any(word in text for word in ("construction", "site", "quality", "safety", "质量", "安全", "施工", "现场")):
            score += 1
        score_by_skill[skill] = score
    best = max(score_by_skill.items(), key=lambda item: item[1])
    return best[0] if best[1] > 0 else SKILLS[-1]


def tool_allowed_for_skill(tool_name: str, skill: SkillDefinition) -> bool:
    name = tool_name or ""
    if skill.skillId == "general_mcp_qa":
        return name.startswith(skill.allowedTools)
    return name in skill.allowedTools or any(name.endswith("." + allowed) for allowed in skill.allowedTools)
