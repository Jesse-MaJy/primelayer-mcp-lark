# MCP Multi-Call Chain And Trace Design

Date: 2026-07-01

## Goal

Build a mixed orchestration flow for Primelayer MCP queries:

- DeepSeek decides which MCP tools to call and returns a list of tool calls.
- Java remains the runtime and security boundary for Feishu identity, project access, token handling, MCP execution, pagination, redaction, trace persistence, and final Feishu replies.
- MCP list-style responses can be paged through deterministically until there is no next page or a configured limit is reached.
- DeepSeek analyzes the collected MCP data and returns the final user-facing answer.
- Admin users can inspect the complete call chain by request ID, including model inputs and outputs, MCP inputs and outputs, page values, failures, and final answer.

The feature applies to both real Feishu message processing and admin debug/test flows. Real Feishu processing has priority, and admin debug should reuse the same runner and trace writer wherever possible.

## Confirmed Decisions

- Use the mixed C approach: Java main orchestration plus DeepSeek structured planning and summarization.
- LangChain is not required for the MVP. The main path can call DeepSeek through plain HTTP and parse JSON responses. The existing Python agent-service and LangGraph/LangChain path can remain as an optional enhancement, not a hard dependency.
- Show trace details with a layered UI: summary and flow graph by default, expandable redacted JSON for details.
- Use deterministic Java pagination as the primary way to collect all list data. DeepSeek can decide whether to request additional different tools after each executed batch.
- Add a dedicated trace event table instead of overloading the existing model/tool audit tables.

## Runtime Architecture

The real Feishu path and admin debug path should share the same core orchestration runner:

1. Receive a Feishu message or admin debug question.
2. Resolve `open_id`, chat type, chat project context, Primelayer user, project candidates, and MCP tokens.
3. Load and filter available MCP tools.
4. Call DeepSeek for structured planning.
5. Validate the returned tool calls against tool whitelist, schema, project access, and read-only safety rules.
6. Execute MCP calls across target projects and requested tools.
7. For each MCP call, automatically collect additional pages when the response exposes a supported pagination shape.
8. Persist trace events for every meaningful step.
9. Return result summaries and failure summaries to DeepSeek for follow-up decision or final summarization.
10. Send the final answer to Feishu or return it to the admin debug endpoint.

Java must never send raw MCP tokens to DeepSeek, Python, or the admin UI. DeepSeek receives tool definitions, user question, project references, and redacted result summaries only.

## DeepSeek Planning Contract

Planning responses should be JSON objects. The MVP contract is:

```json
{
  "needClarification": false,
  "clarificationQuestion": null,
  "projectScope": "single_project | current_chat_project | all_accessible_projects | unknown",
  "projectIds": ["project-id"],
  "toolCalls": [
    {
      "toolName": "primelayer.query_tasks",
      "arguments": {
        "status": "overdue"
      },
      "projectIds": ["project-id"],
      "pagination": {
        "mode": "auto"
      },
      "reason": "查询逾期待办"
    }
  ],
  "finalAnswerReady": false,
  "answer": null
}
```

Rules:

- `toolCalls` is always a list.
- `toolName` must exist in the filtered MCP tool list.
- `arguments` must not contain credentials, tokens, or hidden authorization values.
- `projectIds` must be a subset of Java-resolved project candidates. If empty, Java applies the resolved scope.
- `pagination.mode` defaults to `auto` for read-only list/query/search tools.
- DeepSeek may ask for clarification instead of returning tool calls.
- DeepSeek may return `answer` only when the available context is sufficient and no MCP call is needed.

## Execution Loop

The runner executes up to a configured maximum decision round count.

Per round:

1. Write a `model.plan` trace event with redacted input and output.
2. If clarification is needed, stop and return the clarification question.
3. If final answer is present, stop and return it.
4. For each planned tool call, execute it for each target project.
5. For each project/tool pair, execute the pagination loop.
6. Write `mcp.call` trace events for each page.
7. Build a compact batch result summary.
8. Ask DeepSeek whether another tool batch is needed or a final answer can be produced.

Stop conditions:

- DeepSeek returns a final answer.
- DeepSeek returns no tool calls after receiving tool result summaries.
- Maximum decision rounds is reached.
- The runner has no valid target projects or no valid tools.
- A hard payload limit prevents safe summarization. In this case, the final summary prompt must include truncation metadata.

Partial failures do not fail the entire request. The final answer must mention failed projects/tools and the successful data coverage.

## Pagination Rules

Java handles pagination deterministically. The runner inspects MCP output for common page indicators:

- Cursor fields: `nextCursor`, `next_cursor`, `cursor`.
- Continuation flags: `hasMore`, `has_more`, `more`.
- Page-number fields: `page`, `pageNo`, `pageNum`, `pageSize`, `total`, `totalPages`.
- List containers: `items`, `data`, `records`, `list`, `result.items`, `result.data`.

If cursor pagination is detected, Java copies the next cursor into the next request arguments using the most likely cursor argument key already present in the schema or arguments. If page-number pagination is detected, Java increments the page number. If no supported pagination shape is present, Java treats the response as a single page and does not ask DeepSeek to guess pagination parameters.

Every pagination loop is bounded by configuration:

- Maximum pages per project/tool.
- Maximum items per project/tool.
- Maximum MCP latency per call.
- Maximum bytes retained per trace event.
- Maximum bytes sent into the final DeepSeek summary.

## Trace Event Data Model

Add a new table `agent_trace_event`. Existing `agent_audit_log`, `agent_tool_call_log`, and `agent_model_call_log` remain for compatibility and list views.

Recommended columns:

- `id`
- `request_id`
- `step_index`
- `round_index`
- `parent_step_id`
- `event_type`
- `status`
- `summary`
- `input_json`
- `output_json`
- `error_json`
- `project_id`
- `project_name`
- `primelayer_user_id`
- `tool_name`
- `page_info`
- `latency_ms`
- `created_at`

Event types:

- `request.received`
- `context.resolved`
- `model.plan`
- `mcp.tools_list`
- `mcp.call`
- `model.followup_decision`
- `model.summary`
- `final.answer`
- `request.failed`

The table should have indexes on `request_id`, `created_at`, and optionally `(request_id, step_index)`.

## Redaction And Payload Handling

Only redacted JSON is stored in trace events and returned to the admin UI.

Redaction must cover keys that include or equal:

- `token`
- `authorization`
- `auth`
- `secret`
- `password`
- `apiKey`
- `api_key`
- `ciphertext`
- `mcp_token`
- `access_token`
- `refresh_token`

Large payload handling:

- Preserve counts, project IDs, tool names, page info, status, and latency.
- Keep a small sample of list records for debugging.
- Mark `truncated=true` when input or output exceeds configured limits.
- Include `originalSizeBytes`, `retainedSizeBytes`, `itemCount`, and `pageCount` where possible.
- Summary prompts should receive compact result summaries, not unbounded raw MCP responses.

## Admin UI

The audit list remains lightweight and gains a detail action by `request_id`.

The detail page loads:

- Main audit log row.
- Agent task status.
- Trace events ordered by `step_index`.
- Derived graph or timeline nodes.

The page layout:

- Header: question, request ID, Feishu user, chat, project scope, status, total latency, final answer summary.
- Flow graph or timeline: model planning, MCP calls, page calls, follow-up decisions, summary, final answer.
- Node list: searchable and filterable by event type, project, tool, status.
- Node detail panel: summary, metadata, page info, latency, error, expandable redacted input/output JSON.

The UI should default to summaries and require an explicit expand action to show JSON. If a payload was truncated, the panel should show the truncation metadata clearly.

## Backend APIs

Add an admin detail endpoint:

```text
GET /api/admin/audit-logs/{requestId}
```

The response should include:

- `auditLog`
- `agentTask`
- `traceEvents`
- `graph`

Admin debug endpoints that execute an agent question should return `requestId` and write the same trace events as the Feishu path.

## Error Handling

The runner should distinguish:

- Context resolution errors: missing user binding, missing project token, no accessible project.
- Planning errors: invalid JSON, invalid tool names, invalid project IDs.
- MCP errors: network failure, authentication failure, schema validation failure, page failure.
- Summary errors: DeepSeek unavailable or payload too large.

Fallback behavior:

- If planning fails, use the existing heuristic planner only when it can produce a safe read-only tool call.
- If an MCP page fails, mark that page failed and continue other projects/tools.
- If summary fails after collecting MCP data, return a deterministic fallback answer that includes successful count, failed count, and data coverage.

## Testing And Acceptance Criteria

Backend tests:

- DeepSeek planning response with multiple `toolCalls` is normalized and all calls execute.
- Invalid tool names and project IDs are rejected.
- Cursor pagination collects all pages within limits.
- Page-number pagination collects all pages within limits.
- Missing pagination fields produce a single-page result.
- Sensitive fields are redacted recursively before persistence.
- Large list results are summarized and marked truncated.
- One failed project/tool does not block final summarization.
- Admin detail API returns ordered trace events for a request ID.

Frontend tests or manual verification:

- Audit list opens a request detail page.
- Detail page shows a readable flow or timeline.
- Clicking a node shows metadata and summary.
- Expanding JSON shows redacted input/output.
- Truncated payloads show truncation metadata.
- Failed events are visually distinguishable without breaking the page layout.

End-to-end acceptance:

- A Feishu-style query that requires multiple MCP tools produces multiple MCP trace nodes and one final answer.
- A cross-project query loops through project tokens and reports both coverage and failures.
- An MCP response with pagination is collected through configured limits.
- The final answer is based on MCP data and states data scope.
- The admin can inspect model inputs/outputs and MCP inputs/outputs for the same `request_id`.

## Implementation Notes

Existing useful anchors:

- Java orchestration: `backend/src/main/java/com/larkconnect/agent/agent/AgentOrchestrator.java`
- DeepSeek client: `backend/src/main/java/com/larkconnect/agent/deepseek/DeepSeekClient.java`
- MCP adapter: `backend/src/main/java/com/larkconnect/agent/mcp/McpAdapter.java`
- Audit service: `backend/src/main/java/com/larkconnect/agent/audit/AuditService.java`
- Admin APIs: `backend/src/main/java/com/larkconnect/agent/admin/AdminController.java`
- Admin audit UI: `admin-web/src/views/AuditLogsView.vue`

The implementation should introduce small focused units rather than growing `AgentOrchestrator` too much:

- `McpExecutionRunner` for tool execution, pagination, and result compaction.
- `TraceEventService` for redaction and trace persistence.
- `DeepSeekToolPlanner` or extensions to `DeepSeekClient` for the new planning and follow-up decision contract.
- Admin detail DTOs and API methods for trace visualization.

