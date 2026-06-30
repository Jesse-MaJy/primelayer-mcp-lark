from __future__ import annotations

from fastapi import FastAPI

from .agent_graph import run_agent
from .models import AgentAnswerRequest, AgentAnswerResponse
from .skills import SKILLS

app = FastAPI(title="Lark Connect Agent Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, object]:
    return {"ok": True, "skills": [skill.skillId for skill in SKILLS]}


@app.post("/v1/agent/answer", response_model=AgentAnswerResponse)
def answer(request: AgentAnswerRequest) -> AgentAnswerResponse:
    return run_agent(request)
