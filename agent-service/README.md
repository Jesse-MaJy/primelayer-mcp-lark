# Lark Connect Agent Service

Python Agent Service for business question answering over Primelayer MCP tools.

The Java backend remains the security boundary: it owns Feishu events, user/project permissions, MCP token decryption, MCP `tools/list` and `tools/call`, and audit logs. This service receives only sanitized context and tool schemas, then returns skill routing, project scope, tool calls, and final summaries.

## Run

Use Python 3.10 or newer. On macOS, prefer `python3` or an explicit version such as `python3.12`; an old `python` executable can create a venv that is too old for FastAPI/LangChain.

```bash
cd agent-service
python3 --version
python3 -m venv .venv
. .venv/bin/activate
python --version
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8090
```

If `pip install` says `Ignored the following versions that require a different python version` or `No matching distribution found for fastapi==0.115.6`, the active Python is too old. Recreate the virtual environment with Python 3.10+:

```bash
cd agent-service
deactivate 2>/dev/null || true
rm -rf .venv
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Optional model-backed summarization uses DeepSeek-compatible OpenAI settings:

```bash
export AGENT_MODEL_API_KEY=...
export AGENT_MODEL_BASE_URL=https://api.deepseek.com
export AGENT_MODEL_NAME=deepseek-chat
```

Without model credentials, the service still returns deterministic skill routing, tool plans, and conservative summaries.

## API

`POST /v1/agent/answer`

- Planning phase: omit `toolResults`; the response contains `skillId`, `projectScope`, `projectIds`, and `toolCalls`.
- Summarization phase: include `toolResults`; the response contains `answer` and `answerMetadata`.

MCP tokens must never be sent to this service.
