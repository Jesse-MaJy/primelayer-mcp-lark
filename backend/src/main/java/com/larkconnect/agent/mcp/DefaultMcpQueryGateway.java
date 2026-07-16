package com.larkconnect.agent.mcp;

import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.audit.TraceEventService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.agent.QueryCancelledException;
import com.larkconnect.agent.agent.TaskCancellationGuard;
import com.larkconnect.agent.token.TokenResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

@Service
public class DefaultMcpQueryGateway implements McpQueryGateway {
    private final TokenResolver tokenResolver;
    private final McpAdapter mcpAdapter;
    private final McpToolRegistry toolRegistry;
    private final AuditService auditService;
    private final AppProperties properties;
    private final TraceEventService traceEvents;
    private final McpTraceSampler traceSampler;
    private final TaskCancellationGuard cancellationGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<QueryContext, List<TokenResolver.TokenEntry>> authorizedTokens =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<QueryContext, Map<String, List<Map<String, Object>>>> projectTools =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Autowired
    public DefaultMcpQueryGateway(TokenResolver tokenResolver, McpAdapter mcpAdapter,
                                  McpToolRegistry toolRegistry, AuditService auditService,
                                  AppProperties properties, TraceEventService traceEvents,
                                  TaskCancellationGuard cancellationGuard) {
        this.tokenResolver = tokenResolver;
        this.mcpAdapter = mcpAdapter;
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
        this.properties = properties;
        this.traceEvents = traceEvents;
        this.cancellationGuard = cancellationGuard;
        this.traceSampler = new McpTraceSampler(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    DefaultMcpQueryGateway(TokenResolver tokenResolver, McpAdapter mcpAdapter,
                           McpToolRegistry toolRegistry, AuditService auditService,
                           AppProperties properties) {
        this(tokenResolver, mcpAdapter, toolRegistry, auditService, properties, null, null);
    }

    DefaultMcpQueryGateway(TokenResolver tokenResolver, McpAdapter mcpAdapter,
                           McpToolRegistry toolRegistry, AuditService auditService,
                           AppProperties properties, TraceEventService traceEvents) {
        this(tokenResolver, mcpAdapter, toolRegistry, auditService, properties, traceEvents, null);
    }

    @Override
    public QueryContext loadContext(String openId, String chatId, String chatType) {
        return loadContext(null, openId, chatId, chatType);
    }

    @Override
    public QueryContext loadContext(String requestId, String openId, String chatId, String chatType) {
        return loadContext(requestId, openId, chatId, chatType, List.of());
    }

    @Override
    public QueryContext loadContext(String requestId, String openId, String chatId, String chatType,
                                    List<String> projectIds) {
        TokenResolver.ResolvedContext resolved = projectIds == null || projectIds.isEmpty()
                ? tokenResolver.resolveCandidates(openId, chatId, chatType, properties.agent().maxProjectsPerQuery())
                : tokenResolver.resolveSelected(openId, projectIds);
        if (resolved.hasError()) return new QueryContext(null, List.of(), List.of(), resolved.errorMessage(), List.of());
        List<TokenResolver.TokenEntry> resolvedTokens = resolved.tokens().stream().map(token ->
                token.mcpUserId() == null || token.mcpUserId().isBlank()
                        ? new TokenResolver.TokenEntry(token.tokenId(), token.feishuOpenId(),
                        resolved.primelayerUserId() == null ? "" : resolved.primelayerUserId(),
                        token.projectId(), token.projectName(), token.projectRemark(), token.token())
                        : token).toList();
        List<Project> projects = resolvedTokens.stream()
                .map(token -> new Project(token.projectId(), token.projectName(), token.projectRemark()))
                .toList();
        Map<String, List<Map<String, Object>>> toolsByProject = new LinkedHashMap<>();
        List<String> discoveryFailures = new ArrayList<>();
        List<String> traceEventIds = new ArrayList<>();
        for (TokenResolver.TokenEntry token : resolvedTokens) {
            checkCancelled(requestId);
            TraceEventService.EventHandle trace = startMcpTrace(requestId, "tool_discovery", "MCP 工具发现",
                    token, "tools/list", null, ExecutionTrace.empty(), null, null);
            try {
                Map<String, Object> response;
                if (traceEvents != null && requestId != null) {
                    McpAdapter.ObservedCall observed = mcpAdapter.listToolsObserved(token.token());
                    response = observed.rawResponse();
                    completeMcpTrace(trace, observed, Status.SUCCEEDED, null, null);
                } else {
                    response = mcpAdapter.listTools(token.token());
                }
                toolsByProject.put(token.projectId(), toolRegistry.filterDiscoveredTools(extractTools(response)));
                checkCancelled(requestId);
                addTraceId(traceEventIds, trace);
            } catch (Exception e) {
                failMcpTrace(trace, e);
                addTraceId(traceEventIds, trace);
                toolsByProject.put(token.projectId(), List.of());
                discoveryFailures.add(token.projectName() + "：" + readable(e));
            }
        }
        List<Map<String, Object>> tools = exposeProjectSpecificTools(toolsByProject);
        String availabilityError = !discoveryFailures.isEmpty()
                ? "部分项目 MCP 工具发现失败：" + String.join("；", discoveryFailures)
                : tools.isEmpty() ? "MCP 没有暴露可用的只读工具" : null;
        QueryContext context = new QueryContext(null, projects, tools, availabilityError,
                List.copyOf(traceEventIds));
        authorizedTokens.put(context, List.copyOf(resolvedTokens));
        projectTools.put(context, immutableProjectTools(toolsByProject));
        return context;
    }

    @Override
    public List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                         List<String> projectIds, Map<String, Object> arguments) {
        return execute(requestId, context, toolName, projectIds, arguments, ExecutionTrace.empty());
    }

    @Override
    public List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                         List<String> projectIds, Map<String, Object> arguments,
                                         ExecutionTrace traceContext) {
        checkCancelled(requestId);
        List<TokenResolver.TokenEntry> tokens = authorizedTokens.getOrDefault(context, List.of());
        if (tokens.isEmpty()) throw new IllegalArgumentException("MCP 项目上下文已失效，请重试");
        List<TokenResolver.TokenEntry> targets = targetTokens(tokens, projectIds);
        Map<String, List<Map<String, Object>>> toolsByProject = projectTools.getOrDefault(context, Map.of());
        Map<String, Object> paginationStates = paginationStates(arguments);
        Map<String, Object> publicArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        publicArguments.remove("_paginationStates");
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, Math.max(1, targets.size())));
        try {
            List<Future<ToolObservation>> futures = targets.stream().map(token -> executor.submit(() ->
                    executeTarget(requestId, context, toolName, publicArguments, traceContext,
                            paginationStates, toolsByProject, token))).toList();
            List<ToolObservation> observations = new ArrayList<>();
            for (Future<ToolObservation> future : futures) {
                try {
                    observations.add(future.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("MCP 多项目查询被中断", interrupted);
                } catch (Exception failure) {
                    throw new IllegalStateException("MCP 多项目查询执行失败", failure);
                }
            }
            return observations;
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolObservation executeTarget(String requestId, QueryContext context, String toolName,
                                           Map<String, Object> publicArguments, ExecutionTrace traceContext,
                                           Map<String, Object> paginationStates,
                                           Map<String, List<Map<String, Object>>> toolsByProject,
                                           TokenResolver.TokenEntry token) {
        List<Map<String, Object>> targetTools = toolsByProject.getOrDefault(token.projectId(), List.of());
        TraceEventService.EventHandle validationTrace = null;
        try {
            checkCancelled(requestId);
            Object savedState = paginationStates.get(token.projectId());
            ToolObservation completed = completedObservation(savedState, token, toolName);
            if (completed != null) return completed;
            Map<String, Object> effectiveArguments = effectiveArguments(
                    publicArguments, token.projectId(), token.mcpUserId(), toolName, targetTools);
            validationTrace = startMcpTrace(requestId, "tool_validation", toolName + " · 参数校验",
                    token, toolName, effectiveArguments, traceContext, null, null);
            toolRegistry.validate(toolName, effectiveArguments, targetTools);
            completeMcpTrace(validationTrace,
                    new McpAdapter.ObservedCall(effectiveArguments, Map.of("valid", true), 0, null, null),
                    Status.SUCCEEDED, null, traceMetadata(traceContext));
            McpAdapter.PaginationStyle paginationStyle = paginationStyle(toolName, publicArguments, targetTools);
            return executeForProject(requestId, token.mcpUserId(), token,
                    toolName, effectiveArguments, paginationStyle, traceContext, savedState);
        } catch (Exception e) {
            if (e instanceof QueryCancelledException cancelled) throw cancelled;
            failMcpTrace(validationTrace, e);
            String error = "项目 " + token.projectName() + " / " + toolName + " 校验失败：" + readable(e);
            auditService.writeTool(requestId, token.projectId(), token.mcpUserId(), toolName,
                    publicArguments, Status.FAILED, 0, error);
            return new ToolObservation(token.projectId(), token.projectName(), toolName,
                    Status.FAILED, Map.of(), error, 0, 0, false, null, null, List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> effectiveArguments(Map<String, Object> requestedArguments,
                                                   String projectId, String primelayerUserId,
                                                   String toolName, List<Map<String, Object>> tools) {
        Map<String, Object> effective = new LinkedHashMap<>(
                requestedArguments == null ? Map.of() : requestedArguments);
        List<String> managedArguments = List.of(
                "project_id", "projectId", "primelayer_user_id", "primelayerUserId");
        managedArguments.forEach(effective::remove);
        Set<String> declared = Set.of();
        for (Map<String, Object> tool : tools) {
            if (!toolName.equals(String.valueOf(tool.get("name")))) continue;
            if (!(tool.get("inputSchema") instanceof Map<?, ?> rawSchema)) break;
            Map<String, Object> schema = (Map<String, Object>) rawSchema;
            Set<String> properties = schema.get("properties") instanceof Map<?, ?> rawProperties
                    ? rawProperties.keySet().stream().map(String::valueOf).collect(Collectors.toSet()) : Set.of();
            Set<String> required = schema.get("required") instanceof List<?> list
                    ? list.stream().map(String::valueOf).collect(Collectors.toSet()) : Set.of();
            declared = new java.util.HashSet<>(properties);
            declared.addAll(required);
            break;
        }
        if (hasText(projectId)) {
            if (declared.contains("project_id")) effective.put("project_id", projectId);
            if (declared.contains("projectId")) effective.put("projectId", projectId);
        }
        if (hasText(primelayerUserId)) {
            if (declared.contains("primelayer_user_id")) effective.put("primelayer_user_id", primelayerUserId);
            if (declared.contains("primelayerUserId")) effective.put("primelayerUserId", primelayerUserId);
        }
        return effective;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ToolObservation executeForProject(String requestId, String primelayerUserId,
                                              TokenResolver.TokenEntry token, String toolName,
                                              Map<String, Object> arguments,
                                              McpAdapter.PaginationStyle paginationStyle,
                                              ExecutionTrace traceContext, Object savedState) {
        long started = System.currentTimeMillis();
        try {
            if (paginationStyle == null) {
                TraceEventService.EventHandle trace = startMcpTrace(requestId, "tool_call", toolName,
                        token, toolName, arguments, traceContext, null, null);
                McpAdapter.ObservedCall observed;
                try {
                    observed = mcpAdapter.callToolObserved(token.token(), toolName, arguments);
                    checkCancelled(requestId);
                    McpAdapter.ObservedCall traceObserved = new McpAdapter.ObservedCall(observed.rawRequest(),
                            traceSampler.sample(observed.rawResponse()), observed.latencyMs(),
                            observed.returnedCount(), observed.reportedTotalCount());
                    completeMcpTrace(trace, traceObserved, Status.SUCCEEDED, null,
                            Map.of("logicalCallId", safe(traceContext.logicalCallId())));
                } catch (Exception e) {
                    failMcpTrace(trace, e);
                    throw e;
                }
                Map<String, Object> response = observed.rawResponse();
                NormalizedMcpPayload normalized = NormalizedMcpPayload.from(objectMapper, response);
                AsyncTaskState asyncState = AsyncTaskState.from(normalized.businessPayload());
                long latency = observed.latencyMs();
                auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolName, arguments, Status.SUCCEEDED, latency, null);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("result", response);
                payload.put("businessPayload", normalized.businessPayload());
                if (!normalized.items().isEmpty()) payload.put("records", normalized.items());
                if ("get_async_task_result".equals(toolName) && asyncState.known()) {
                    payload.put("asyncTaskState", asyncState.value());
                    payload.put("asyncTaskTerminal", asyncState.terminal());
                    payload.put("asyncTaskSuccessful", asyncState.successful());
                }
                return new ToolObservation(token.projectId(), token.projectName(), toolName, Status.SUCCEEDED,
                        payload, null, 1, 1, false,
                        observed.returnedCount(), observed.reportedTotalCount(), traceIds(trace));
            }
            List<String> pageTraceIds = Collections.synchronizedList(new ArrayList<>());
            McpAdapter.PaginationContinuation continuation = continuation(savedState);
            McpAdapter.PaginationBatchResult batch = mcpAdapter.callToolWithPaginationBatch(
                    token.token(), toolName, arguments, 100, paginationStyle, new McpAdapter.PaginationObserver() {
                        @Override
                        public Object onStart(int pageIndex, int pageSize, Map<String, Object> rawRequest) {
                            checkCancelled(requestId);
                            TraceEventService.EventHandle handle = startMcpTrace(requestId, "tool_call",
                                    toolName + " · 第 " + (pageIndex + 1) + " 页", token, toolName,
                                    rawRequest, traceContext, pageIndex + 1, pageSize);
                            addTraceId(pageTraceIds, handle);
                            return handle;
                        }

                        @Override
                        public void onComplete(Object observerContext, McpAdapter.PageData page) {
                            if (!(observerContext instanceof TraceEventService.EventHandle handle)) return;
                            McpAdapter.ObservedCall observed = new McpAdapter.ObservedCall(
                                    page.rawRequest(), page.rawResponse() == null ? Map.of() : traceSampler.sample(page.rawResponse()), page.latencyMs(),
                                    page.returnedCount(), page.reportedTotalCount());
                            if (Status.SUCCEEDED.equals(page.status())) {
                                Map<String, Object> metadata = new LinkedHashMap<>(traceMetadata(traceContext));
                                metadata.put("cumulativeFetchedCount", page.cumulativeFetchedCount());
                                metadata.put("reportedTotalCount", page.reportedTotalCount());
                                metadata.put("coveragePercent", page.coveragePercent());
                                metadata.put("duplicatePage", page.duplicate());
                                completeMcpTrace(handle, observed, page.status(), page.error(),
                                        metadata);
                            } else {
                                traceEvents.complete(handle, TraceEventService.EventCompletion.failed()
                                        .input(page.rawRequest()).error(Map.of("message", safe(page.error())))
                                        .metadata(traceMetadata(traceContext)).build());
                            }
                        }
                    }, properties.agent().queryPageBatchSize(), continuation);
            McpAdapter.PaginationResult result = batch.result();
            for (McpAdapter.PageData page : result.pages()) {
                auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolName, arguments,
                        page.status(), page.latencyMs(), page.error());
            }
            boolean truncated = result.limitReached();
            String status = result.successPages() == 0 ? Status.FAILED
                    : result.failedPages() > 0 || truncated || !result.coverageComplete() ? "PARTIAL" : Status.SUCCEEDED;
            String error = result.failedPages() > 0 ? "项目 " + token.projectName() + " 的部分分页查询失败"
                    : truncated ? "项目 " + token.projectName() + " 自动分页达到 30 分钟截止时间"
                    : !result.coverageComplete() ? "项目 " + token.projectName() + " 分页覆盖不完整：已获取 "
                            + result.fetchedCount() + "/" + (result.reportedTotalCount() == null ? "未知" : result.reportedTotalCount())
                            + "（" + result.incompleteReason() + "）" : null;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("statistics", result.statistics());
            payload.put("evidenceSamples", result.items());
            payload.put("totalCount", result.totalCount());
            payload.put("fetchedCount", result.fetchedCount());
            payload.put("reportedTotalCount", result.reportedTotalCount());
            payload.put("coverageComplete", result.coverageComplete());
            payload.put("incompleteReason", result.incompleteReason());
            payload.put("pageCount", result.totalPages());
            payload.put("pageLimitReached", truncated);
            if (!batch.complete()) {
                payload.put("asyncTaskState", "PAGINATING");
                payload.put("asyncTaskTerminal", false);
                payload.put("paginationState", state(false, batch.continuation(), null, null));
                return new ToolObservation(token.projectId(), token.projectName(), toolName, "PENDING", payload, null,
                        result.pages().size(), result.pages().size(), false,
                        result.fetchedCount(), result.reportedTotalCount(), List.copyOf(pageTraceIds));
            }
            payload.put("paginationState", state(true, null, payload, status));
            return new ToolObservation(token.projectId(), token.projectName(), toolName, status, payload, error,
                    result.pages().size(), result.pages().size(), truncated,
                    result.fetchedCount(), result.reportedTotalCount(), List.copyOf(pageTraceIds));
        } catch (Exception e) {
            if (e instanceof QueryCancelledException cancelled) throw cancelled;
            if (retryable(e)) {
                long retryAfterSeconds = retryAfterSeconds(e);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("asyncTaskState", "RETRYING");
                payload.put("asyncTaskTerminal", false);
                payload.put("retryable", true);
                if (retryAfterSeconds > 0) payload.put("retryAfterSeconds", retryAfterSeconds);
                payload.put("lastError", readable(e));
                return new ToolObservation(token.projectId(), token.projectName(), toolName, "RETRYING",
                        payload, null, 1, 0, false, null, null, List.of());
            }
            String error = "项目 " + token.projectName() + " / " + toolName + " 查询失败：" + readable(e);
            auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolName, arguments,
                    Status.FAILED, System.currentTimeMillis() - started, error);
            return new ToolObservation(token.projectId(), token.projectName(), toolName, Status.FAILED,
                    Map.of(), error, 1, 0, false, null, null, List.of());
        }
    }

    private boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResourceAccessException) return true;
            if (current instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                return status == 429 || status >= 500;
            }
            current = current.getCause();
        }
        return false;
    }

    private void checkCancelled(String requestId) {
        if (cancellationGuard != null) cancellationGuard.check(requestId);
    }

    private long retryAfterSeconds(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RestClientResponseException response && response.getResponseHeaders() != null) {
                String value = response.getResponseHeaders().getFirst("Retry-After");
                try { return value == null ? 0 : Math.max(0, Long.parseLong(value.trim())); }
                catch (NumberFormatException ignored) { return 0; }
            }
            current = current.getCause();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paginationStates(Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("_paginationStates") instanceof Map<?, ?> states)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        states.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @SuppressWarnings("unchecked")
    private McpAdapter.PaginationContinuation continuation(Object state) {
        if (!(state instanceof Map<?, ?> raw) || Boolean.TRUE.equals(raw.get("completed"))) return null;
        Object value = raw.get("continuation");
        return value == null ? null : objectMapper.convertValue(value, McpAdapter.PaginationContinuation.class);
    }

    @SuppressWarnings("unchecked")
    private ToolObservation completedObservation(Object state, TokenResolver.TokenEntry token, String toolName) {
        if (!(state instanceof Map<?, ?> raw) || !Boolean.TRUE.equals(raw.get("completed"))
                || !(raw.get("payload") instanceof Map<?, ?> savedPayload)) return null;
        Map<String, Object> payload = new LinkedHashMap<>();
        savedPayload.forEach((key, value) -> payload.put(String.valueOf(key), value));
        payload.put("paginationState", state);
        String status = String.valueOf(raw.containsKey("status") ? raw.get("status") : Status.SUCCEEDED);
        return new ToolObservation(token.projectId(), token.projectName(), toolName, status, payload, null,
                0, 0, false, number(payload.get("fetchedCount")), number(payload.get("reportedTotalCount")), List.of());
    }

    private Map<String, Object> state(boolean completed, McpAdapter.PaginationContinuation continuation,
                                      Map<String, Object> payload, String status) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("completed", completed);
        if (continuation != null) state.put("continuation", continuation);
        if (payload != null) {
            Map<String, Object> copy = new LinkedHashMap<>(payload);
            copy.remove("paginationState");
            state.put("payload", copy);
        }
        if (status != null) state.put("status", status);
        return state;
    }

    private Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private TraceEventService.EventHandle startMcpTrace(String requestId, String purpose, String label,
                                                        TokenResolver.TokenEntry token, String toolName,
                                                        Object input, ExecutionTrace context,
                                                        Integer pageIndex, Integer pageSize) {
        if (traceEvents == null || requestId == null) return null;
        return traceEvents.start(TraceEventService.EventStart.builder(requestId, "mcp_call")
                .parentEventId(context.parentEventId()).dependencyEventIds(context.dependencyEventIds())
                .roundIndex(context.roundIndex()).purpose(purpose).label(label)
                .projectId(token.projectId()).projectName(token.projectName()).toolName(toolName)
                .pageIndex(pageIndex).pageSize(pageSize).input(input)
                .metadata(context.logicalCallId() == null ? Map.of() : Map.of("logicalCallId", context.logicalCallId()))
                .build());
    }

    private void completeMcpTrace(TraceEventService.EventHandle trace, McpAdapter.ObservedCall observed,
                                  String status, String error, Map<String, Object> metadata) {
        if (traceEvents == null || trace == null) return;
        TraceEventService.EventCompletion completion = TraceEventService.EventCompletion.succeeded()
                .status(status).input(observed.rawRequest()).output(observed.rawResponse())
                .returnedCount(observed.returnedCount()).reportedTotalCount(observed.reportedTotalCount());
        if (error != null) completion.error(Map.of("message", error));
        if (metadata != null) completion.metadata(metadata);
        traceEvents.complete(trace, completion.build());
    }

    private void failMcpTrace(TraceEventService.EventHandle trace, Throwable error) {
        if (traceEvents != null && trace != null) traceEvents.fail(trace, error);
    }

    private void addTraceId(List<String> ids, TraceEventService.EventHandle trace) {
        if (trace != null && trace.persisted()) ids.add(trace.eventId());
    }

    private List<String> traceIds(TraceEventService.EventHandle trace) {
        return trace == null || !trace.persisted() ? List.of() : List.of(trace.eventId());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> traceMetadata(ExecutionTrace context) {
        Map<String, Object> metadata = new LinkedHashMap<>(context.metadata());
        if (context.logicalCallId() != null) metadata.put("logicalCallId", context.logicalCallId());
        return metadata;
    }

    private List<TokenResolver.TokenEntry> targetTokens(List<TokenResolver.TokenEntry> tokens, List<String> projectIds) {
        if ((projectIds == null || projectIds.isEmpty()) && tokens.size() == 1) return tokens;
        if (projectIds == null || projectIds.isEmpty()) throw new IllegalArgumentException("多个项目可用时必须明确 projectIds");
        Set<String> allowed = tokens.stream().map(TokenResolver.TokenEntry::projectId).collect(Collectors.toSet());
        for (String id : projectIds) if (!allowed.contains(id)) throw new IllegalArgumentException("无权查询项目：" + id);
        Set<String> requested = Set.copyOf(projectIds);
        return tokens.stream().filter(token -> requested.contains(token.projectId())).toList();
    }

    @SuppressWarnings("unchecked")
    private McpAdapter.PaginationStyle paginationStyle(String toolName, Map<String, Object> arguments,
                                                       List<Map<String, Object>> tools) {
        for (Map<String, Object> tool : tools) {
            if (!toolName.equals(String.valueOf(tool.get("name")))) continue;
            Object schema = tool.get("inputSchema");
            if (schema instanceof Map<?, ?> schemaMap) {
                Object props = ((Map<String, Object>) schemaMap).get("properties");
                if (props instanceof Map<?, ?> propertiesMap) {
                    Set<?> keys = propertiesMap.keySet();
                    if (keys.contains("cursor") || keys.contains("pageToken") || keys.contains("page_token")) {
                        return McpAdapter.PaginationStyle.CURSOR;
                    }
                    if ((arguments.containsKey("offset") || arguments.containsKey("limit"))
                            && keys.contains("offset") && keys.contains("limit")) {
                        return McpAdapter.PaginationStyle.OFFSET_LIMIT;
                    }
                    if ((arguments.containsKey("page") || arguments.containsKey("pageSize"))
                            && keys.contains("page") && keys.contains("pageSize")) {
                        return McpAdapter.PaginationStyle.PAGE_SIZE;
                    }
                    if (keys.contains("page") && keys.contains("pageSize")) return McpAdapter.PaginationStyle.PAGE_SIZE;
                    if (keys.contains("offset") && keys.contains("limit")) return McpAdapter.PaginationStyle.OFFSET_LIMIT;
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> exposeProjectSpecificTools(
            Map<String, List<Map<String, Object>>> toolsByProject) {
        List<Map<String, Object>> exposed = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : toolsByProject.entrySet()) {
            for (Map<String, Object> tool : entry.getValue()) {
                Map<String, Object> match = exposed.stream()
                        .filter(existing -> compatibleDefinition(existing, tool))
                        .findFirst().orElse(null);
                if (match == null) {
                    Map<String, Object> copy = new LinkedHashMap<>(tool);
                    copy.put("supportedProjectIds", new ArrayList<>(List.of(entry.getKey())));
                    exposed.add(copy);
                } else {
                    @SuppressWarnings("unchecked")
                    List<String> supported = (List<String>) match.get("supportedProjectIds");
                    supported.add(entry.getKey());
                }
            }
        }
        return exposed.stream().map(tool -> {
            Map<String, Object> copy = new LinkedHashMap<>(tool);
            @SuppressWarnings("unchecked")
            List<String> supported = List.copyOf((List<String>) tool.get("supportedProjectIds"));
            copy.put("supportedProjectIds", supported);
            copy.put("description", String.valueOf(tool.getOrDefault("description", tool.get("name")))
                    + "（支持项目：" + String.join(", ", supported) + "）");
            return Collections.unmodifiableMap(copy);
        }).toList();
    }

    private Map<String, List<Map<String, Object>>> immutableProjectTools(
            Map<String, List<Map<String, Object>>> toolsByProject) {
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        toolsByProject.forEach((projectId, tools) -> copy.put(projectId, List.copyOf(tools)));
        return Collections.unmodifiableMap(copy);
    }

    private boolean compatibleDefinition(Map<String, Object> left, Map<String, Object> right) {
        return String.valueOf(left.get("name")).equals(String.valueOf(right.get("name")))
                && java.util.Objects.equals(left.get("inputSchema"), right.get("inputSchema"))
                && java.util.Objects.equals(left.get("annotations"), right.get("annotations"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> map && ((Map<?, ?>) map).get("tools") instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        if (response.get("tools") instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }

    private String readable(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "MCP 请求失败" : message;
    }

}
