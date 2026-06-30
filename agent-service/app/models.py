from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class UserContext(BaseModel):
    openId: str | None = None
    primelayerUserId: str | None = None


class ProjectRef(BaseModel):
    projectId: str
    projectName: str | None = None


class ToolDefinition(BaseModel):
    name: str
    description: str | None = None
    inputSchema: dict[str, Any] | None = None


class ToolCall(BaseModel):
    toolName: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    projectIds: list[str] = Field(default_factory=list)
    reason: str | None = None


class AgentAnswerRequest(BaseModel):
    requestId: str
    question: str
    chatType: str = "p2p"
    userContext: UserContext = Field(default_factory=UserContext)
    projects: list[ProjectRef] = Field(default_factory=list)
    availableTools: list[ToolDefinition] = Field(default_factory=list)
    history: list[dict[str, Any]] = Field(default_factory=list)
    toolResults: list[dict[str, Any]] = Field(default_factory=list)


class AgentAnswerResponse(BaseModel):
    needClarification: bool = False
    clarificationQuestion: str | None = None
    skillId: str = "general_mcp_qa"
    projectScope: str = "unknown"
    projectIds: list[str] = Field(default_factory=list)
    toolCalls: list[ToolCall] = Field(default_factory=list)
    answer: str | None = None
    answerMetadata: dict[str, Any] = Field(default_factory=dict)
