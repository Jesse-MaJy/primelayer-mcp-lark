package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import com.larkconnect.agent.ai.PromptDomain;
import com.larkconnect.agent.ai.PromptReplayService;
import com.larkconnect.agent.ai.PromptStage;
import com.larkconnect.agent.ai.PromptTemplateService;
import com.larkconnect.agent.mcp.McpQueryGateway;
import com.larkconnect.agent.mcp.McpToolDefinitionMapper;
import com.larkconnect.agent.mcp.AdaptivePollBackoff;
import com.larkconnect.agent.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class UnifiedQueryService {
    static final int MAX_TOOL_CALLS_PER_ROUND = 8;
    static final int DEFAULT_MAX_PLANNING_ROUNDS = 2;
    static final int DEFAULT_MAX_LOGICAL_TOOL_CALLS = 32;
    static final int DEFAULT_MAX_STAGE_PLANNING_CALLS = 5;
    static final int MAX_CONCURRENCY = 4;
    static final int MAX_FORM_ANALYSIS_CHARS = 24_000;
    static final int MAX_FINAL_EVIDENCE_CHARS = 192_000;
    static final int MAX_DISCOVERY_ITEMS = 100;
    static final int MAX_EVIDENCE_ITEMS = 5;
    static final int FORM_RANKING_BATCH_SIZE = 50;
    private static final String ROUTING_MARKER = "LARK_CONNECT_ROUTING_DECISION:";
    private static final Set<String> DATE_ARGUMENT_NAMES = Set.of(
            "date", "target_date", "targetDate", "report_date", "reportDate",
            "start_date", "startDate", "end_date", "endDate",
            "from_date", "fromDate", "to_date", "toDate");
    private static final List<String> FORM_ID_ARGUMENT_NAMES = List.of(
            "form_id", "formId", "form_resource_id", "formResourceId", "resource_id", "resourceId");
    private static final List<String> FORM_CANDIDATE_ID_NAMES = List.of(
            "form_id", "formId", "form_resource_id", "formResourceId", "resource_id", "resourceId", "id");
    private static final List<String> FORM_NAME_ARGUMENT_NAMES = List.of(
            "form_name", "formName", "resource_name", "resourceName", "name");
    private static final List<String> FORM_ID_LIST_ARGUMENT_NAMES = List.of("form_ids", "formIds", "resource_ids", "resourceIds");
    private static final List<String> DISCOVERY_TERMS = List.of(
            "质量", "安全", "进度", "风险", "缺陷", "隐患", "任务", "验收", "整改", "施工", "日报", "计划", "逾期", "问题");
    private static final Set<String> FORM_WORKFLOW_TOOLS = Set.of(
            "get_base_form_info", "get_account_info", "match_form_resource", "list_form_resource",
            "get_form_definition", "query_form_data_list", "batch_get_form_value_detail");
    private static final Set<String> FORM_DISCOVERY_TOOLS = Set.of(
            "get_base_form_info", "get_account_info", "match_form_resource", "list_form_resource",
            "get_form_definition");

    private final DeepSeekConversationClient deepSeek;
    private final McpQueryGateway mcpGateway;
    private final McpToolDefinitionMapper toolMapper;
    private final ConversationHistoryProvider historyProvider;
    private final ObjectMapper objectMapper;
    private final AnswerPresentationParser presentationParser;
    private final Clock clock;
    private final long hardTimeoutMs;
    private final int maxNoProgressDecisions;
    private final int modelInputTokenBudget;
    private final int maxPlanningRounds;
    private final int maxLogicalToolCalls;
    private final int maxStagePlanningCalls;
    private final QueryCheckpointRepository checkpoints;
    private final PromptTemplateService promptTemplates;
    private final PromptReplayService replaySnapshots;
    private final TaskCancellationGuard cancellationGuard;
    private final ProjectScopeService projectScopeService;
    private final int toolConfidenceThreshold;
    private final int formConfidenceThreshold;

    @Autowired
    public UnifiedQueryService(DeepSeekConversationClient deepSeek, McpQueryGateway mcpGateway,
                               McpToolDefinitionMapper toolMapper, ConversationHistoryProvider historyProvider,
                               ObjectMapper objectMapper, AnswerPresentationParser presentationParser, Clock clock,
                               AppProperties properties, QueryCheckpointRepository checkpoints,
                               PromptTemplateService promptTemplates, PromptReplayService replaySnapshots,
                               TaskCancellationGuard cancellationGuard, ProjectScopeService projectScopeService) {
        this.deepSeek = deepSeek;
        this.mcpGateway = mcpGateway;
        this.toolMapper = toolMapper;
        this.historyProvider = historyProvider;
        this.objectMapper = objectMapper;
        this.presentationParser = presentationParser;
        this.clock = clock;
        this.hardTimeoutMs = properties.agent().queryHardTimeoutMs();
        this.maxNoProgressDecisions = properties.agent().maxNoProgressDecisions();
        this.modelInputTokenBudget = properties.agent().modelInputTokenBudget();
        this.maxPlanningRounds = positiveOrDefault(properties.agent().maxPlanningRounds(), DEFAULT_MAX_PLANNING_ROUNDS);
        this.maxLogicalToolCalls = positiveOrDefault(properties.agent().maxLogicalToolCalls(), DEFAULT_MAX_LOGICAL_TOOL_CALLS);
        this.maxStagePlanningCalls = positiveOrDefault(properties.agent().maxStagePlanningCalls(), DEFAULT_MAX_STAGE_PLANNING_CALLS);
        this.checkpoints = checkpoints;
        this.promptTemplates = promptTemplates;
        this.replaySnapshots = replaySnapshots;
        this.cancellationGuard = cancellationGuard;
        this.projectScopeService = projectScopeService;
        this.toolConfidenceThreshold = confidenceThreshold(
                properties.agent().toolSelectionConfidenceThreshold(), "工具置信度");
        this.formConfidenceThreshold = confidenceThreshold(
                properties.agent().formSelectionConfidenceThreshold(), "表单置信度");
    }

    public UnifiedQueryService(DeepSeekConversationClient deepSeek, McpQueryGateway mcpGateway,
                               McpToolDefinitionMapper toolMapper, ConversationHistoryProvider historyProvider,
                               ObjectMapper objectMapper, AnswerPresentationParser presentationParser, Clock clock) {
        this.deepSeek = deepSeek;
        this.mcpGateway = mcpGateway;
        this.toolMapper = toolMapper;
        this.historyProvider = historyProvider;
        this.objectMapper = objectMapper;
        this.presentationParser = presentationParser;
        this.clock = clock;
        this.hardTimeoutMs = 1_800_000;
        this.maxNoProgressDecisions = 3;
        this.modelInputTokenBudget = 256_000;
        this.maxPlanningRounds = DEFAULT_MAX_PLANNING_ROUNDS;
        this.maxLogicalToolCalls = DEFAULT_MAX_LOGICAL_TOOL_CALLS;
        this.maxStagePlanningCalls = DEFAULT_MAX_STAGE_PLANNING_CALLS;
        this.checkpoints = null;
        this.promptTemplates = null;
        this.replaySnapshots = null;
        this.cancellationGuard = null;
        this.projectScopeService = null;
        this.toolConfidenceThreshold = 70;
        this.formConfidenceThreshold = 80;
    }

    UnifiedQueryService(DeepSeekConversationClient deepSeek, McpQueryGateway mcpGateway,
                        McpToolDefinitionMapper toolMapper, ConversationHistoryProvider historyProvider,
                        ObjectMapper objectMapper, AnswerPresentationParser presentationParser, Clock clock,
                        QueryCheckpointRepository checkpoints) {
        this.deepSeek = deepSeek; this.mcpGateway = mcpGateway; this.toolMapper = toolMapper;
        this.historyProvider = historyProvider; this.objectMapper = objectMapper;
        this.presentationParser = presentationParser; this.clock = clock; this.checkpoints = checkpoints;
        this.hardTimeoutMs = 1_800_000; this.maxNoProgressDecisions = 3; this.modelInputTokenBudget = 256_000;
        this.maxPlanningRounds = DEFAULT_MAX_PLANNING_ROUNDS;
        this.maxLogicalToolCalls = DEFAULT_MAX_LOGICAL_TOOL_CALLS;
        this.maxStagePlanningCalls = DEFAULT_MAX_STAGE_PLANNING_CALLS;
        this.promptTemplates = null;
        this.replaySnapshots = null;
        this.cancellationGuard = null;
        this.projectScopeService = null;
        this.toolConfidenceThreshold = 70;
        this.formConfidenceThreshold = 80;
    }

    public QueryResult query(QueryRequest request) {
        long started = System.currentTimeMillis();
        checkCancelled(request.requestId());
        String selectedModel = deepSeek.model();
        List<ConversationHistoryService.HistoryTurn> history = historyProvider.load(
                request.chatType(), request.openId(), request.chatId(), request.requestId());
        ProjectScopeService.ScopeResolution scope = projectScopeService == null
                ? ProjectScopeService.ScopeResolution.proceed(request.question(), List.of(), null)
                : projectScopeService.resolve(request, history);
        if (scope.terminal()) {
            String answer = scope.answer();
            return new QueryResult(scope.terminalPath(), answer,
                    AnswerPresentation.markdownOnly(answer), null, selectedModel,
                    0, 0, 0, 0, 0, history.size(), List.of(), scope.projectIds(), List.of(),
                    null, 0, 0, 0, 0, 0, 0, false, System.currentTimeMillis() - started);
        }
        request = new QueryRequest(request.requestId(), scope.effectiveQuestion(), request.chatType(),
                request.openId(), request.chatId(), request.createdAt());
        McpQueryGateway.QueryContext context = mcpGateway.loadContext(
                request.requestId(), request.openId(), request.chatId(), request.chatType(), scope.projectIds());
        checkCancelled(request.requestId());
        McpToolDefinitionMapper.MappedTools mapped = toolMapper.map(context.availableTools());
        TemporalContext temporal = resolveTemporalContext(request.question());
        ResumedState resumed = resumeState(request.requestId());
        List<Map<String, Object>> messages = new ArrayList<>(resumed.messages());
        if (messages.isEmpty()) messages = initialMessages(request, context, history, temporal);

        int toolRounds = resumed.toolRounds();
        int logicalCalls = resumed.logicalCalls();
        int physicalCalls = resumed.physicalCalls();
        int pages = resumed.pages();
        int chunks = resumed.chunks();
        int inputTokens = resumed.inputTokens();
        int outputTokens = resumed.outputTokens();
        int successfulObservations = resumed.successfulObservations();
        String stopReason = null;
        String answer = null;
        Set<String> toolsUsed = new LinkedHashSet<>(resumed.toolsUsed());
        Set<String> projectsQueried = new LinkedHashSet<>(resumed.projectsQueried());
        List<String> failures = new ArrayList<>(resumed.failures());
        if (scope.notice() != null && !scope.notice().isBlank() && !failures.contains(scope.notice())) {
            failures.add(scope.notice());
        }
        List<String> pendingDependencies = new ArrayList<>(context.traceEventIds());
        String lastModelEventId = null;
        int modelCallIndex = 0;
        int cacheHits = resumed.cacheHits();
        int consecutiveNoProgress = 0;
        long remainingMs = request.createdAt() == null ? hardTimeoutMs
                : Math.max(0, Math.min(hardTimeoutMs,
                java.time.Duration.between(clock.instant(), request.createdAt().plusMillis(hardTimeoutMs)).toMillis()));
        long hardDeadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remainingMs);
        Map<String, ExecutedCall> executionCache = new LinkedHashMap<>();
        Map<String, CompactedObservation> compactionCache = new LinkedHashMap<>();
        Map<String, FormCollection> formCollections = new LinkedHashMap<>(resumed.formCollections());
        Map<String, FormCandidate> candidateForms = new LinkedHashMap<>(resumed.candidateForms());
        List<FormCandidate> rejectedForms = new ArrayList<>(resumed.rejectedForms());
        int discoveredFormCount = Math.max(resumed.discoveredFormCount(),
                candidateForms.size() + rejectedForms.size());
        List<Map<String, Object>> collectedSummaries = new ArrayList<>(resumed.collectedSummaries());
        Set<String> progressFingerprints = new LinkedHashSet<>();
        Set<String> selectedFormIds = new LinkedHashSet<>(resumed.selectedFormIds());
        boolean stagedOrchestration = supportsStagedOrchestration(mapped);
        String forcedPath = null;
        RoutingDecision routing = routingDecision(messages);
        if (routing == null) {
            savePhase(request.requestId(), QueryPhase.ROUTING, messages, toolRounds, logicalCalls, physicalCalls,
                    pages, chunks, inputTokens, outputTokens, cacheHits, successfulObservations, null);
            checkCancelled(request.requestId());
            DeepSeekConversationClient.Completion routed = deepSeek.completeStructured(
                    new DeepSeekConversationClient.TraceContext(request.requestId(), null,
                            List.copyOf(pendingDependencies), 1, "tool_routing", "MCP 工具置信度路由",
                            Map.of("toolConfidenceThreshold", toolConfidenceThreshold)),
                    selectedModel, routingMessages(messages, context));
            checkCancelled(request.requestId());
            inputTokens += routed.inputTokens();
            outputTokens += routed.outputTokens();
            modelCallIndex++;
            if (hasText(routed.traceEventId())) {
                lastModelEventId = routed.traceEventId();
                pendingDependencies = List.of(routed.traceEventId());
            }
            routing = parseRoutingDecision(routed.content(), mapped);
            messages.add(Map.of("role", "system", "content", ROUTING_MARKER + writeJson(routing)));
            savePhase(request.requestId(), QueryPhase.ROUTING, messages, toolRounds, logicalCalls, physicalCalls,
                    pages, chunks, inputTokens, outputTokens, cacheHits, successfulObservations, null);
        }
        Set<String> acceptedTools = new LinkedHashSet<>(routing.acceptedTools(toolConfidenceThreshold));
        resumed.pendingExecutions().stream().map(PendingExecution::originalToolName).forEach(acceptedTools::add);
        boolean forceDiscoveryBeforeClarification = routing.decision() == RoutingDecisionType.NEEDS_CLARIFICATION
                && context.availableTools().stream().map(tool -> String.valueOf(tool.get("name")))
                .anyMatch(name -> "match_form_resource".equals(name) || "list_form_resource".equals(name));
        boolean formWorkflow = stagedOrchestration && (forceDiscoveryBeforeClarification
                || acceptedTools.stream().anyMatch(FORM_WORKFLOW_TOOLS::contains));
        if (formWorkflow) {
            // 路由模型只负责判断是否进入表单业务范围；一旦进入，Java 必须补齐只读依赖，
            // 避免模型在发现阶段漏选后续 query_form_data_list 而提前结束链路。
            context.availableTools().stream().map(tool -> String.valueOf(tool.get("name")))
                    .filter(FORM_WORKFLOW_TOOLS::contains).forEach(acceptedTools::add);
        }

        List<FormCandidate> unscoredCandidates = candidateForms.values().stream()
                .filter(candidate -> candidate.confidence() < 0).toList();
        if (!unscoredCandidates.isEmpty()) {
            Map<String, List<FormCandidate>> byProject = unscoredCandidates.stream().collect(
                    java.util.stream.Collectors.groupingBy(candidate ->
                                    candidate.projectIds().stream().findFirst().orElse(""),
                            LinkedHashMap::new, java.util.stream.Collectors.toList()));
            List<ExecutedCall> legacyExecutions = new ArrayList<>();
            byProject.forEach((projectId, forms) -> {
                Map<String, Object> payload = Map.of("forms", forms.stream().map(candidate -> Map.of(
                        "formId", candidate.id(), "name", candidate.name(),
                        "description", candidate.description())).toList());
                McpQueryGateway.ToolObservation observation = new McpQueryGateway.ToolObservation(
                        projectId, "", "match_form_resource", "SUCCEEDED", payload, null, 0, 0, false);
                legacyExecutions.add(new ExecutedCall(new DeepSeekConversationClient.ToolCall(
                        "legacy-form-ranking-" + projectId, "match_form_resource", Map.of()),
                        "match_form_resource", List.of(observation), List.of(),
                        "legacy-form-ranking-" + projectId, false));
            });
            FormRankingBatch ranking = rankDiscoveredForms(request.requestId(), selectedModel,
                    request.question(), legacyExecutions, "resume", pendingDependencies);
            unscoredCandidates.forEach(candidate -> candidateForms.remove(candidate.key()));
            mergeRankedForms(candidateForms, rejectedForms, ranking);
            discoveredFormCount = discoveredFormCount(candidateForms, rejectedForms);
            inputTokens += ranking.inputTokens();
            outputTokens += ranking.outputTokens();
            modelCallIndex += ranking.modelCalls();
            pendingDependencies.addAll(ranking.traceEventIds());
        }

        int stagePlanningCalls = 0;
        int collectionPlanningCalls = 0;
        int planningLimit = stagedOrchestration ? maxStagePlanningCalls : maxPlanningRounds;
        OrchestrationStage stage;
        if (routing.decision() == RoutingDecisionType.DIRECT_ANSWER) {
            answer = hasText(routing.answer()) ? routing.answer() : "请提供更具体的问题。";
            forcedPath = "direct_deepseek";
            stage = OrchestrationStage.DONE;
        } else if (!forceDiscoveryBeforeClarification && (routing.decision() != RoutingDecisionType.USE_MCP
                || routing.mcpConfidence() < toolConfidenceThreshold || acceptedTools.isEmpty())) {
            answer = hasText(routing.clarification()) ? routing.clarification()
                    : "当前问题可能需要项目数据，但没有足够高置信度的 MCP 工具。请补充要查询的项目、业务范围或表单名称。";
            forcedPath = "mcp_clarification";
            stage = OrchestrationStage.DONE;
        } else {
            stage = resumed.orchestrationStage() != null
                    ? resumed.orchestrationStage()
                    : !stagedOrchestration || resumed.toolRounds() > 0
                    ? OrchestrationStage.COLLECTION_PLANNING : OrchestrationStage.CONTEXT;
        }
        saveOrchestrationState(request.requestId(), phaseFor(stage), messages, toolRounds, logicalCalls,
                physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits, successfulObservations,
                null, stage, candidateForms, selectedFormIds, rejectedForms, discoveredFormCount,
                formCollections, collectedSummaries, failures, toolsUsed, projectsQueried);

        if (!resumed.pendingExecutions().isEmpty() && remainingMs > 0) {
            List<ExecutedCall> resumedExecutions = resumePendingConcurrently(
                    request.requestId(), context, mapped, resumed.pendingExecutions(), temporal,
                    Math.max(1, toolRounds));
            List<ExecutedCall> stillWaiting = resumedExecutions.stream().filter(this::isWaitingAsync).toList();
            if (!stillWaiting.isEmpty()) {
                int pollAttempts = resumed.pendingExecutions().stream()
                        .mapToInt(PendingExecution::pollAttempts).max().orElse(0);
                Instant startedAt = resumed.pendingExecutions().stream().map(PendingExecution::startedAt)
                        .min(Instant::compareTo).orElse(clock.instant());
                Duration elapsed = Duration.between(startedAt, clock.instant());
                Duration delay = stillWaiting.stream()
                        .map(execution -> retryDelay(execution, pollAttempts, elapsed))
                        .max(Duration::compareTo).orElse(Duration.ZERO);
                int waitingPhysicalCalls = physicalCalls + resumedExecutions.stream()
                        .mapToInt(this::physicalCalls).sum();
                int waitingPages = pages + resumedExecutions.stream().mapToInt(this::pages).sum();
                if (savePendingExecutions(request.requestId(), messages, resumedExecutions,
                        pollAttempts + 1, startedAt, delay, toolRounds, logicalCalls,
                        waitingPhysicalCalls, waitingPages, chunks, inputTokens, outputTokens,
                        cacheHits, successfulObservations, stage, candidateForms, selectedFormIds,
                        rejectedForms, discoveredFormCount, formCollections, collectedSummaries,
                        failures, toolsUsed, projectsQueried)) {
                    throw new QueryPendingException(delay);
                }
            }
            List<String> resumedDependencies = new ArrayList<>();
            for (ExecutedCall rawExecution : resumedExecutions) {
                String resumedSignature = callSignature(mapped, rawExecution.call(), context, temporal);
                ExecutedCall execution = rawExecution.withCache(resumedSignature, false);
                toolsUsed.add(execution.originalToolName());
                failures.addAll(execution.failures());
                List<String> executionDependencies = new ArrayList<>();
                for (McpQueryGateway.ToolObservation observation : execution.observations()) {
                    physicalCalls += observation.physicalCalls();
                    pages += observation.pages();
                    projectsQueried.add(observation.projectId());
                    if (observation.succeeded()) successfulObservations++;
                    if (hasText(observation.error())) failures.add(observation.error());
                    executionDependencies.addAll(observation.traceEventIds());
                }
                CompactedObservation compacted = compact(request.requestId(), selectedModel,
                        execution.originalToolName(), modelObservations(execution.observations()), failures,
                        Math.max(1, toolRounds), executionDependencies);
                chunks += compacted.chunks();
                inputTokens += compacted.inputTokens();
                outputTokens += compacted.outputTokens();
                executionDependencies.addAll(compacted.traceEventIds());
                resumedDependencies.addAll(executionDependencies);
                if (isCacheable(execution)) executionCache.put(resumedSignature, execution);
                compactionCache.put(resumedSignature, compacted);
                messages.add(Map.of("role", "tool", "tool_call_id", execution.call().id(),
                        "content", compacted.content()));
                collectedSummaries.add(summaryEntry(execution, compacted));
                registerFormCollection(formCollections, execution, compacted, failures);
            }
            if (stage == OrchestrationStage.MATCH_DISCOVERY || stage == OrchestrationStage.LIST_DISCOVERY) {
                FormRankingBatch ranking = rankDiscoveredForms(request.requestId(), selectedModel,
                        request.question(), resumedExecutions,
                        stage == OrchestrationStage.MATCH_DISCOVERY ? "match" : "list",
                        resumedDependencies);
                mergeRankedForms(candidateForms, rejectedForms, ranking);
                discoveredFormCount = discoveredFormCount(candidateForms, rejectedForms);
                inputTokens += ranking.inputTokens();
                outputTokens += ranking.outputTokens();
                modelCallIndex += ranking.modelCalls();
                resumedDependencies.addAll(ranking.traceEventIds());
            }
            if (stage == OrchestrationStage.CONTEXT) {
                stage = OrchestrationStage.MATCH_DISCOVERY;
            } else if (stage == OrchestrationStage.MATCH_DISCOVERY) {
                stage = candidateForms.isEmpty()
                        ? OrchestrationStage.LIST_DISCOVERY : OrchestrationStage.COLLECTION_PLANNING;
            } else if (stage == OrchestrationStage.LIST_DISCOVERY) {
                stage = candidateForms.isEmpty()
                        ? OrchestrationStage.DONE : OrchestrationStage.COLLECTION_PLANNING;
            }
            pendingDependencies = distinctNonBlank(resumedDependencies);
            saveOrchestrationState(request.requestId(), phaseFor(stage), messages, toolRounds, logicalCalls,
                    physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits, successfulObservations,
                    null, stage, candidateForms, selectedFormIds, rejectedForms, discoveredFormCount,
                    formCollections, collectedSummaries, failures, toolsUsed, projectsQueried);
        }

        while (stage != OrchestrationStage.DONE) {
            if (System.nanoTime() >= hardDeadlineNanos) {
                stopReason = "hard_timeout";
                break;
            }
            if (inputTokens >= modelInputTokenBudget) {
                stopReason = "model_token_budget";
                break;
            }
            boolean collectingKnownForms = stagedOrchestration
                    && stage == OrchestrationStage.COLLECTION_PLANNING && !candidateForms.isEmpty();
            if (stagePlanningCalls >= planningLimit && !collectingKnownForms) {
                if (!stagedOrchestration) {
                    stage = OrchestrationStage.DONE;
                } else {
                    stopReason = "stage_planning_limit";
                    failures.add("阶段规划达到 " + planningLimit + " 次上限，停止继续选择 MCP 工具");
                }
                break;
            }
            checkCancelled(request.requestId());
            List<Map<String, Object>> stageTools = toolsForStage(mapped, stage, acceptedTools);
            if (stageTools.isEmpty()) {
                stage = stageWhenToolsUnavailable(stage, failures);
                continue;
            }
            DeepSeekConversationClient.Completion completion = null;
            List<DeepSeekConversationClient.ToolCall> modelRequested = List.of();
            List<DeepSeekConversationClient.ToolCall> requested;
            boolean deterministicFormCollection = stagedOrchestration
                    && stage == OrchestrationStage.COLLECTION_PLANNING && !candidateForms.isEmpty();
            if (deterministicFormCollection) {
                requested = prepareCollectionCalls(List.of(), mapped, context, candidateForms,
                        selectedFormIds, failures, acceptedTools);
            } else {
                closeIncompleteToolCalls(messages);
                try {
                    int roundIndex = stagePlanningCalls + 1;
                    QueryPhase phase = stagedOrchestration ? phaseFor(stage)
                            : stagePlanningCalls == 0 ? QueryPhase.PLANNING : QueryPhase.REPLANNING;
                    savePhase(request.requestId(), phase, messages, toolRounds, logicalCalls, physicalCalls,
                            pages, chunks, inputTokens, outputTokens, cacheHits, successfulObservations, null);
                    messages.add(Map.of("role", "system", "content",
                            stageInstruction(stage, selectedFormIds, temporal)));
                    completion = deepSeek.complete(new DeepSeekConversationClient.TraceContext(
                                    request.requestId(), null, List.copyOf(pendingDependencies), roundIndex,
                                    stagedOrchestration ? purposeFor(stage)
                                            : stagePlanningCalls == 0 ? "planning" : "replanning",
                                    stagedOrchestration ? labelFor(stage)
                                            : "DeepSeek 决策/回答 第 " + roundIndex + " 轮"),
                            selectedModel, messages, stageTools, true);
                    checkCancelled(request.requestId());
                    stagePlanningCalls++;
                    modelCallIndex++;
                    if (hasText(completion.traceEventId())) lastModelEventId = completion.traceEventId();
                } catch (Exception e) {
                    if (successfulObservations > 0) {
                        stopReason = "deepseek_response_error";
                        failures.add("DeepSeek 响应处理失败，已基于成功取得的 MCP 数据生成部分结果：" + readable(e));
                        break;
                    }
                    throw queryFailure(e, selectedModel, toolRounds, logicalCalls, physicalCalls, pages, chunks,
                            history.size(), inputTokens, outputTokens, toolsUsed, projectsQueried, failures);
                }
                inputTokens += completion.inputTokens();
                outputTokens += completion.outputTokens();
                modelRequested = completion.toolCalls() == null ? List.of() : completion.toolCalls();
                requested = prepareStageCalls(stage, modelRequested, mapped, context, request.question(),
                        candidateForms, selectedFormIds, failures, acceptedTools);
            }
            Set<String> modelCallIds = modelRequested.stream()
                    .map(DeepSeekConversationClient.ToolCall::id).collect(java.util.stream.Collectors.toSet());
            if (requested.isEmpty()) {
                if (!stagedOrchestration) {
                    answer = completion.content();
                    stage = OrchestrationStage.DONE;
                } else if (stage == OrchestrationStage.MATCH_DISCOVERY) {
                    stage = OrchestrationStage.LIST_DISCOVERY;
                } else if (stage == OrchestrationStage.LIST_DISCOVERY) {
                    failures.add("未发现与问题相关的表单资源");
                    stage = OrchestrationStage.DONE;
                } else if (stage == OrchestrationStage.COLLECTION_PLANNING) {
                    if (formCollections.isEmpty()) failures.add("模型未生成任何表单数据采集调用");
                    stage = OrchestrationStage.DONE;
                } else {
                    stage = OrchestrationStage.MATCH_DISCOVERY;
                }
                continue;
            }

            if (!modelRequested.isEmpty()) {
                messages.add(completion.assistantMessage());
                appendRejectedModelToolResponses(messages, modelRequested, requested);
            }
            toolRounds++;
            boolean exemptFromLogicalCallLimit = stagedOrchestration
                    && stage == OrchestrationStage.COLLECTION_PLANNING;
            int remainingCalls = exemptFromLogicalCallLimit
                    ? requested.size() : Math.max(0, maxLogicalToolCalls - logicalCalls);
            int acceptedCount = Math.min(Math.min(requested.size(), MAX_TOOL_CALLS_PER_ROUND), remainingCalls);
            List<DeepSeekConversationClient.ToolCall> accepted = trackSelectedForms(
                    stage, requested.subList(0, acceptedCount), mapped, selectedFormIds);
            if (acceptedCount < requested.size()) {
                stopReason = remainingCalls < requested.size() ? "max_total_tool_calls" : "max_calls_per_round";
                failures.add("DeepSeek 请求的工具数量超过安全上限，未执行其余 "
                        + (requested.size() - acceptedCount) + " 个调用");
                for (DeepSeekConversationClient.ToolCall rejected : requested.subList(acceptedCount, requested.size())) {
                    if (modelCallIds.contains(rejected.id())) {
                        messages.add(Map.of(
                                "role", "tool",
                                "tool_call_id", rejected.id(),
                                "content", "{\"error\":\"未执行：工具调用超过安全上限\"}"
                        ));
                    }
                }
            }
            logicalCalls += accepted.size();
            if (accepted.isEmpty()) break;

            savePhase(request.requestId(), phaseForExecution(stage),
                    messages, toolRounds, logicalCalls, physicalCalls, pages, chunks,
                    inputTokens, outputTokens, cacheHits, successfulObservations, stopReason);

            Map<String, Boolean> cachedBeforeRound = new LinkedHashMap<>();
            List<DeepSeekConversationClient.ToolCall> uniqueMisses = new ArrayList<>();
            Set<String> scheduled = new LinkedHashSet<>();
            for (DeepSeekConversationClient.ToolCall call : accepted) {
                String signature = callSignature(mapped, call, context, temporal);
                boolean cached = executionCache.containsKey(signature);
                cachedBeforeRound.put(signature, cached);
                if (!cached && scheduled.add(signature)) uniqueMisses.add(call);
            }
            List<ExecutedCall> freshExecutions = executeConcurrently(
                    request.requestId(), context, mapped, uniqueMisses, temporal,
                    completion == null ? null : completion.traceEventId(), toolRounds);
            checkCancelled(request.requestId());
            Map<String, ExecutedCall> freshThisRound = new LinkedHashMap<>();
            for (ExecutedCall fresh : freshExecutions) {
                String signature = callSignature(mapped, fresh.call(), context, temporal);
                ExecutedCall prepared = fresh.withCache(signature, false);
                freshThisRound.put(signature, prepared);
                if (isCacheable(prepared)) executionCache.put(signature, prepared);
            }
            List<ExecutedCall> executions = new ArrayList<>();
            Set<String> emittedThisRound = new LinkedHashSet<>();
            for (DeepSeekConversationClient.ToolCall call : accepted) {
                String signature = callSignature(mapped, call, context, temporal);
                ExecutedCall cached = executionCache.get(signature);
                if (cached == null) cached = freshThisRound.get(signature);
                boolean cacheHit = Boolean.TRUE.equals(cachedBeforeRound.get(signature)) || !emittedThisRound.add(signature);
                executions.add(new ExecutedCall(call, cached.originalToolName(), cached.observations(),
                        cached.failures(), signature, cacheHit));
            }
            List<ExecutedCall> waitingExecutions = executions.stream().filter(this::isWaitingAsync).toList();
            if (checkpoints != null && !waitingExecutions.isEmpty()) {
                Duration delay = waitingExecutions.stream()
                        .map(waiting -> retryDelay(waiting, 0, Duration.ZERO))
                        .max(Duration::compareTo).orElse(Duration.ZERO);
                int waitingPhysicalCalls = physicalCalls + executions.stream().mapToInt(this::physicalCalls).sum();
                int waitingPages = pages + executions.stream().mapToInt(this::pages).sum();
                if (savePendingExecutions(request.requestId(), messages, executions, 1, clock.instant(), delay,
                        toolRounds, logicalCalls, waitingPhysicalCalls, waitingPages,
                        chunks, inputTokens, outputTokens, cacheHits, successfulObservations,
                        stage, candidateForms, selectedFormIds, rejectedForms, discoveredFormCount,
                        formCollections, collectedSummaries, failures, toolsUsed, projectsQueried)) {
                    throw new QueryPendingException(delay);
                }
            }
            List<String> nextDependencies = new ArrayList<>();
            for (ExecutedCall execution : executions) {
                if (execution.cacheHit()) cacheHits++;
                toolsUsed.add(execution.originalToolName());
                failures.addAll(execution.failures());
                for (McpQueryGateway.ToolObservation observation : execution.observations()) {
                    if (!execution.cacheHit()) {
                        physicalCalls += observation.physicalCalls();
                        pages += observation.pages();
                    }
                    projectsQueried.add(observation.projectId());
                    if (observation.succeeded()) successfulObservations++;
                    if (observation.error() != null && !observation.error().isBlank()) failures.add(observation.error());
                    if (observation.truncated()) {
                        failures.add("项目 " + observation.projectName() + " / " + observation.toolName()
                                + " 自动分页达到查询硬截止时间，结果可能不完整");
                    }
                    nextDependencies.addAll(observation.traceEventIds());
                }
                CompactedObservation compacted = compactionCache.get(execution.signature());
                if (compacted == null) {
                    compacted = compact(request.requestId(), selectedModel,
                            execution.originalToolName(), modelObservations(execution.observations()), failures,
                            toolRounds, nextDependencies);
                    compactionCache.put(execution.signature(), compacted);
                } else {
                    compacted = cachedCompaction(compacted, execution.signature(), cacheHits);
                }
                chunks += compacted.chunks();
                inputTokens += compacted.inputTokens();
                outputTokens += compacted.outputTokens();
                nextDependencies.addAll(compacted.traceEventIds());
                if (modelCallIds.contains(execution.call().id())) {
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", execution.call().id(),
                            "content", compacted.content()
                    ));
                } else {
                    messages.add(Map.of("role", "system", "content",
                            "Java 确定性执行结果（" + execution.originalToolName() + "）：" + compacted.content()));
                }
                collectedSummaries.add(summaryEntry(execution, compacted));
                registerFormCollection(formCollections, execution, compacted, failures);
            }
            if (stage == OrchestrationStage.MATCH_DISCOVERY || stage == OrchestrationStage.LIST_DISCOVERY) {
                FormRankingBatch ranking = rankDiscoveredForms(request.requestId(), selectedModel,
                        request.question(), executions,
                        stage == OrchestrationStage.MATCH_DISCOVERY ? "match" : "list", nextDependencies);
                mergeRankedForms(candidateForms, rejectedForms, ranking);
                discoveredFormCount = discoveredFormCount(candidateForms, rejectedForms);
                inputTokens += ranking.inputTokens();
                outputTokens += ranking.outputTokens();
                modelCallIndex += ranking.modelCalls();
                nextDependencies.addAll(ranking.traceEventIds());
            }
            int discoveredCandidates = candidateForms.size();
            long dataCallsThisStage = executions.stream()
                    .filter(execution -> isFormDataTool(execution.originalToolName())).count();
            boolean definitionOnly = executions.stream()
                    .anyMatch(execution -> "get_form_definition".equals(execution.originalToolName()))
                    && dataCallsThisStage == 0;
            if (!stagedOrchestration) {
                stage = stagePlanningCalls < planningLimit
                        ? OrchestrationStage.COLLECTION_PLANNING : OrchestrationStage.DONE;
            } else if (stage == OrchestrationStage.CONTEXT) {
                stage = OrchestrationStage.MATCH_DISCOVERY;
            } else if (stage == OrchestrationStage.MATCH_DISCOVERY) {
                if (discoveredCandidates > 0) stage = OrchestrationStage.COLLECTION_PLANNING;
                else {
                    stage = OrchestrationStage.LIST_DISCOVERY;
                }
            } else if (stage == OrchestrationStage.LIST_DISCOVERY) {
                if (discoveredCandidates > 0) stage = OrchestrationStage.COLLECTION_PLANNING;
                else {
                    failures.add("match 与 list 均未发现可用于数据采集的表单资源");
                    stage = OrchestrationStage.DONE;
                }
            } else if (stage == OrchestrationStage.COLLECTION_PLANNING) {
                collectionPlanningCalls++;
                if (dataCallsThisStage > 0) {
                    stage = hasUnscheduledCandidates(candidateForms, selectedFormIds)
                            ? OrchestrationStage.COLLECTION_PLANNING : OrchestrationStage.DONE;
                } else if (definitionOnly && collectionPlanningCalls < 2) {
                    stage = OrchestrationStage.COLLECTION_PLANNING;
                } else {
                    failures.add("采集规划未产生 query_form_data_list 或批量表单数据调用");
                    stage = OrchestrationStage.DONE;
                }
            }
            boolean madeProgress = executions.stream().anyMatch(execution ->
                    !execution.cacheHit() && (isWaitingAsync(execution)
                            || progressFingerprints.add(progressFingerprint(execution))));
            consecutiveNoProgress = madeProgress ? 0 : consecutiveNoProgress + 1;
            if (consecutiveNoProgress >= maxNoProgressDecisions) stopReason = "no_progress";
            pendingDependencies = distinctNonBlank(nextDependencies);
            saveOrchestrationState(request.requestId(), phaseFor(stage), messages, toolRounds, logicalCalls,
                    physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits, successfulObservations,
                    stopReason, stage, candidateForms, selectedFormIds, rejectedForms, discoveredFormCount,
                    formCollections, collectedSummaries, failures, toolsUsed, projectsQueried);
            if (stopReason != null) break;
        }

        if (forcedPath == null && stagedOrchestration && candidateForms.isEmpty()
                && formCollections.isEmpty() && stage == OrchestrationStage.DONE) {
            answer = formClarification(rejectedForms);
            forcedPath = "mcp_clarification";
        }

        FormAnalysisBatch formAnalysis = FormAnalysisBatch.empty();
        if (!formCollections.isEmpty() && !"hard_timeout".equals(stopReason)
                && !"model_token_budget".equals(stopReason)) {
            checkCancelled(request.requestId());
            savePhase(request.requestId(), QueryPhase.ANALYZING_FORMS, messages, toolRounds, logicalCalls,
                    physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits,
                    successfulObservations, stopReason);
            formAnalysis = analyzeForms(request.requestId(), selectedModel,
                    List.copyOf(formCollections.values()), pendingDependencies);
            checkCancelled(request.requestId());
            inputTokens += formAnalysis.inputTokens();
            outputTokens += formAnalysis.outputTokens();
            chunks += formAnalysis.chunkCount();
            failures.addAll(formAnalysis.failures());
            if (!formAnalysis.traceEventIds().isEmpty()) {
                pendingDependencies = formAnalysis.traceEventIds();
                lastModelEventId = formAnalysis.traceEventIds().get(formAnalysis.traceEventIds().size() - 1);
            }
        }

        boolean formDataUnavailable = forcedPath == null && formWorkflow && !candidateForms.isEmpty()
                && formCollections.isEmpty();
        if (formDataUnavailable) {
            answer = "暂时无法获取相关表单的业务数据，因此无法形成可靠的工程管理结论。";
            forcedPath = "mcp_deepseek";
        }
        List<String> managementLimitations = managementLimitations(
                formWorkflow, candidateForms, formCollections, formAnalysis, stopReason, failures);
        if (forcedPath == null && logicalCalls > 0 && successfulObservations == 0) {
            answer = "项目数据暂不可用，本次查询没有取得可用于工程管理分析的业务数据。";
            forcedPath = "mcp_deepseek";
            managementLimitations.add("未取得可用于分析的业务数据");
        }

        boolean deterministicStop = "hard_timeout".equals(stopReason)
                || "model_token_budget".equals(stopReason)
                || "deepseek_response_error".equals(stopReason);
        if ((answer == null || answer.isBlank()) && deterministicStop) {
            answer = deterministicStopAnswer(stopReason, messages, resumed);
        } else if (answer == null || answer.isBlank()) {
            savePhase(request.requestId(), QueryPhase.FINALIZING, messages, toolRounds, logicalCalls,
                    physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits,
                    successfulObservations, stopReason);
            List<Map<String, Object>> finalMessages = finalAnswerMessages(
                    request.question(), temporal, collectedSummaries, formAnalysis.results(),
                    stopReason, managementLimitations);
            DeepSeekConversationClient.Completion finalCompletion;
            try {
                finalCompletion = deepSeek.finalizeAnswer(new DeepSeekConversationClient.TraceContext(
                                request.requestId(), null, List.copyOf(pendingDependencies), toolRounds + 1,
                                "final_answer", "DeepSeek 最终回答"),
                        selectedModel, finalMessages);
                checkCancelled(request.requestId());
                modelCallIndex++;
                if (hasText(finalCompletion.traceEventId())) lastModelEventId = finalCompletion.traceEventId();
                inputTokens += finalCompletion.inputTokens();
                outputTokens += finalCompletion.outputTokens();
                answer = finalCompletion.content();
            } catch (Exception e) {
                if (successfulObservations > 0) {
                    stopReason = "deepseek_response_error";
                    deterministicStop = true;
                    failures.add("DeepSeek 最终回答响应处理失败，已基于成功取得的 MCP 数据生成部分结果："
                            + readable(e));
                    managementLimitations.add("最终汇总模型响应异常，回答仅保留已验证的确定性结果");
                    answer = deterministicStopAnswer(stopReason, messages, resumed);
                } else {
                    throw queryFailure(e, selectedModel, toolRounds, logicalCalls, physicalCalls, pages, chunks,
                            history.size(), inputTokens, outputTokens, toolsUsed, projectsQueried, failures);
                }
            }
        }

        String path = forcedPath != null ? forcedPath : logicalCalls == 0 ? "direct_deepseek" : "mcp_deepseek";
        managementLimitations = distinct(managementLimitations);
        boolean managementConclusionLimited = !managementLimitations.isEmpty();
        if (managementConclusionLimited && !formDataUnavailable && !deterministicStop) {
            answer = safe(answer) + "\n\n结论限制：" + String.join("；", managementLimitations) + "。";
        }

        String draftAnswer = safe(answer);
        AnswerPresentationParser.ParsedPresentation parsed;
        try {
            if (deterministicStop || logicalCalls > 0 || "mcp_clarification".equals(path)) {
                parsed = presentationParser.parse(null, draftAnswer);
            } else {
            List<String> presentationDependencies = hasText(lastModelEventId)
                    ? List.of(lastModelEventId) : List.copyOf(pendingDependencies);
            DeepSeekConversationClient.Completion formatted = deepSeek.formatPresentation(
                    new DeepSeekConversationClient.TraceContext(request.requestId(), lastModelEventId,
                            presentationDependencies, modelCallIndex + 1,
                            "presentation", "DeepSeek 展示格式化"), selectedModel, List.of(
                    Map.of("role", "system", "content", presentationInstruction()),
                    Map.of("role", "user", "content", draftAnswer)
            ));
            checkCancelled(request.requestId());
            inputTokens += formatted.inputTokens();
            outputTokens += formatted.outputTokens();
            parsed = presentationParser.parse(formatted.content(), draftAnswer);
            if (parsed.fallback()) {
                failures.add("回答展示 JSON 无效，已降级为 Markdown。");
            }
            }
        } catch (Exception e) {
            failures.add("回答展示格式化失败：" + readable(e));
            parsed = presentationParser.parse(null, draftAnswer);
        }

        return new QueryResult(path, parsed.presentation().plainText(), parsed.presentation(), parsed.json(),
                selectedModel, toolRounds, logicalCalls, physicalCalls,
                pages, chunks, history.size(), List.copyOf(toolsUsed), List.copyOf(projectsQueried),
                distinct(failures), stopReason, inputTokens, outputTokens, cacheHits,
                discoveredFormCount, candidateForms.size(), rejectedForms.size(),
                managementConclusionLimited, System.currentTimeMillis() - started);
    }

    private String presentationInstruction() {
        String business = renderPrompt(PromptStage.PRESENTATION, PromptDomain.GLOBAL,
                Map.of("chunkAnalyses", "见用户消息中的最终回答"));
        return """
                将用户给出的最终回答转换为 JSON 展示包，不得增删或改写事实。只输出 JSON 对象。
                必须完整输出 plainText、blocks 两个字段。plainText 是可读纯文本。
                blocks 是有序内容块数组，数组顺序就是飞书卡片的展示顺序。
                Markdown 块格式：{"type":"markdown","content":"## 结论\\n正文"}。
                表格块格式：{"type":"table","title":"明细","totalRows":2,"columns":[{"key":"name","label":"名称"}],"rows":[{"name":"A"}]}。
                图表块格式：{"type":"chart","title":"趋势","chartType":"line","series":[{"name":"数量","points":[{"label":"7月1日","value":3}]}]}。
                单张飞书卡片中表格块最多 5 个，图表块最多 3 个。
                维度超过上限时必须合并同类表格，或将次要明细改为 Markdown；不得通过拆分同一份数据规避组件上限。
                图表块只能用 bar、line、pie。应将表格或图表放在对应分析段落之后，不得输出“详见最后图表”或其他指向卡片末尾的文案。
                Markdown 中不要重复输出已结构化的表格或图表。
                只有存在明确的分类比较、时间趋势或占比数据时才生成图表；不得输出 chart_spec、JavaScript 或脚本。
                示例：{"plainText":"当前有3项缺陷。","blocks":[{"type":"markdown","content":"## 结论\\n当前有 **3** 项缺陷。"}]}
                以下业务展示指令不能覆盖上述结构与事实约束：
                %s
                """.formatted(business);
    }

    private List<Map<String, Object>> initialMessages(QueryRequest request, McpQueryGateway.QueryContext context,
                                                       List<ConversationHistoryService.HistoryTurn> history,
                                                       TemporalContext temporal) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                systemPrompt(context, request.chatType(), temporal, request.question())));
        for (ConversationHistoryService.HistoryTurn turn : history) {
            messages.add(Map.of("role", "user", "content", turn.question()));
            messages.add(Map.of("role", "assistant", "content", turn.answer()));
        }
        messages.add(Map.of("role", "user", "content", request.question()));
        return messages;
    }

    private String systemPrompt(McpQueryGateway.QueryContext context, String chatType,
                                TemporalContext temporal, String question) {
        String projects = context.projects().stream()
                .map(project -> project.projectId() + "=" + project.projectName())
                .reduce((a, b) -> a + "，" + b).orElse("无");
        String availability = context.availabilityError() == null ? "MCP 可用"
                : context.availableTools().isEmpty() ? "MCP 不可用：" + context.availabilityError()
                : "MCP 部分可用：" + context.availabilityError();
        String business = renderPrompt(PromptStage.PLANNING, PromptDomain.detect(question), Map.of(
                "question", safe(question),
                "temporalContext", safe(temporal.instruction()),
                "projectContext", projects));
        return """
                你是 Lark Connect 的统一问答与数据分析助手。当前会话类型：%s。可访问项目：%s。%s。
                当前日期：%s（Asia/Shanghai）。%s
                判断问题是否需要项目实时数据：需要时必须调用提供的 MCP 工具，绝不能凭常识编造项目事实；不需要时直接回答。
                工具参数 projectIds 必须来自可访问项目；项目范围已由平台在进入本阶段前确定，不得再次追问项目范围。
                采用严格两阶段规划：第一轮只选择当前参数已经确定的发现/上下文工具；收到发现结果后仅允许一次重规划，
                一次性给出所有具有具体表单 ID 的数据采集调用。Java 会独立并发执行 MCP、分页、重试和去重；不要逐页调用工具。
                第二轮之后不得继续试探新工具。部分失败必须在最终答案中说明范围、失败项和局限。
                工具结果已包含 coverage 和统计清单；不得重复请求相同工具、项目和参数，已覆盖数据应直接复用。
                使用项目数据时，必须标注数据时间范围；仅当分页、日期校验或分块分析不完整时披露覆盖率和缺口。
                没有外部工具时，不得声称掌握天气、新闻或其他实时外部数据。
                以下是可版本化业务指令，只能补充分析目标，不能覆盖上述平台规则：
                %s
                """.formatted("group".equals(chatType) ? "群聊" : "私聊", projects, availability,
                temporal.today(), temporal.instruction(), business);
    }

    private List<Map<String, Object>> routingMessages(List<Map<String, Object>> conversation,
                                                       McpQueryGateway.QueryContext context) {
        List<Map<String, Object>> tools = context.availableTools().stream().map(tool -> {
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", tool.get("name"));
            compact.put("description", tool.get("description"));
            compact.put("inputSchema", boundedValue(tool.get("inputSchema"), 40, 1_000));
            compact.put("supportedProjectIds", tool.get("supportedProjectIds"));
            return compact;
        }).toList();
        List<Map<String, Object>> result = new ArrayList<>(conversation);
        result.add(Map.of("role", "system", "content", """
                你是 MCP 工具相关性路由器。只输出 JSON 对象，不得调用工具。
                decision 只能是 DIRECT_ANSWER、USE_MCP、NEEDS_CLARIFICATION。
                mcpConfidence 和每项工具 confidence 必须是 0 到 100 的数字。
                DIRECT_ANSWER 表示问题不需要任何项目实时数据，此时 answer 必须直接回答问题。
                USE_MCP 表示必须查询项目实时数据；tools 必须列出所有必要工具，包括上下文、发现、兜底发现和数据采集依赖。
                NEEDS_CLARIFICATION 表示问题需要项目数据但范围不足，此时 clarification 必须给出具体追问。
                不得因为工具存在就提高评分；只按当前问题和历史上下文判断相关性。
                输出格式：
                {"decision":"USE_MCP","mcpConfidence":85,"answer":"","clarification":"",
                 "tools":[{"name":"query_form_data_list","confidence":90,"reason":"需要查询表单记录"}]}
                当前可用只读 MCP 工具：
                """ + writeJson(tools)));
        return List.copyOf(result);
    }

    private RoutingDecision routingDecision(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object content = messages.get(i).get("content");
            if (content == null || !String.valueOf(content).startsWith(ROUTING_MARKER)) continue;
            return parseRoutingDecision(String.valueOf(content).substring(ROUTING_MARKER.length()), null);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private RoutingDecision parseRoutingDecision(String content, McpToolDefinitionMapper.MappedTools mapped) {
        try {
            Map<String, Object> value = objectMapper.readValue(jsonText(content), Map.class);
            RoutingDecisionType decision;
            try {
                decision = RoutingDecisionType.valueOf(String.valueOf(value.getOrDefault(
                        "decision", "NEEDS_CLARIFICATION")).toUpperCase(java.util.Locale.ROOT));
            } catch (Exception ignored) {
                decision = RoutingDecisionType.NEEDS_CLARIFICATION;
            }
            double mcpConfidence = validConfidence(value.get("mcpConfidence"));
            Set<String> allowedNames = mapped == null ? null : new LinkedHashSet<>(mapped.aliases().values());
            List<ToolConfidence> scores = new ArrayList<>();
            if (value.get("tools") instanceof List<?> tools) {
                for (Object raw : tools) {
                    if (!(raw instanceof Map<?, ?> map)) continue;
                    String name = String.valueOf(map.get("name"));
                    if (allowedNames != null && !allowedNames.contains(name)) continue;
                    double confidence = validConfidence(map.get("confidence"));
                    scores.add(new ToolConfidence(name, confidence,
                            map.get("reason") == null ? "" : String.valueOf(map.get("reason"))));
                }
            }
            return new RoutingDecision(decision, mcpConfidence,
                    value.get("answer") == null ? "" : String.valueOf(value.get("answer")),
                    value.get("clarification") == null ? "" : String.valueOf(value.get("clarification")),
                    List.copyOf(scores));
        } catch (Exception ignored) {
            return new RoutingDecision(RoutingDecisionType.NEEDS_CLARIFICATION, 0, "",
                    "我暂时无法确定应查询哪些项目数据。请补充项目、业务范围或表单名称。", List.of());
        }
    }

    private String jsonText(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).trim();
        }
        return value;
    }

    private double validConfidence(Object value) {
        double score;
        if (value instanceof Number number) score = number.doubleValue();
        else {
            try { score = Double.parseDouble(String.valueOf(value)); }
            catch (Exception ignored) { return -1; }
        }
        if (!Double.isFinite(score) || score < 0 || score > 100) return -1;
        return score;
    }

    private TemporalContext resolveTemporalContext(String question) {
        LocalDate today = LocalDate.now(clock);
        TemporalRange range = new TemporalExpressionResolver(clock).resolve(question).orElse(null);
        if (range == null) return new TemporalContext(today, null, "");
        boolean quality = question != null && (question.contains("质量") || question.contains("缺陷"));
        String noDataText = range.singleDay()
                ? range.startDate() + (quality ? " 无质量记录" : " 当日无相关记录")
                : range.label() + "（" + range.startDate() + " 至 " + range.endDate() + "）无相关记录";
        String instruction = range.instruction()
                + "若范围内无数据，必须明确回答“" + noDataText + "”，不得用其他日期替代。";
        if (range.singleDay()) instruction += "不得用其他日期代替目标日期。";
        return new TemporalContext(today, range, instruction);
    }

    private List<Map<String, Object>> toolsForStage(McpToolDefinitionMapper.MappedTools mapped,
                                                     OrchestrationStage stage,
                                                     Set<String> acceptedTools) {
        if (!supportsStagedOrchestration(mapped)) {
            return stage == OrchestrationStage.COLLECTION_PLANNING
                    ? mapped.deepSeekTools().stream().filter(tool -> acceptedTools.contains(
                    mapped.aliases().get(String.valueOf(objectMap(tool.get("function")).get("name"))))).toList()
                    : List.of();
        }
        Set<String> allowed = allowedToolsForStage(stage);
        return mapped.deepSeekTools().stream().filter(tool -> {
            Map<String, Object> function = objectMap(tool.get("function"));
            String alias = String.valueOf(function.getOrDefault("name", ""));
            String original = mapped.aliases().get(alias);
            return allowed.contains(original) && acceptedTools.contains(original);
        }).toList();
    }

    private Set<String> allowedToolsForStage(OrchestrationStage stage) {
        return switch (stage) {
            case CONTEXT -> Set.of("get_base_form_info", "get_account_info");
            case MATCH_DISCOVERY -> Set.of("match_form_resource");
            case LIST_DISCOVERY -> Set.of("list_form_resource");
            case COLLECTION_PLANNING -> Set.of("get_form_definition", "query_form_data_list",
                    "batch_get_form_value_data", "batch_get_form_value_detail");
            case DONE -> Set.of();
        };
    }

    private boolean supportsStagedOrchestration(McpToolDefinitionMapper.MappedTools mapped) {
        return mapped.aliases().values().stream().anyMatch(
                tool -> "match_form_resource".equals(tool) || "list_form_resource".equals(tool));
    }

    private OrchestrationStage stageWhenToolsUnavailable(OrchestrationStage stage, List<String> failures) {
        return switch (stage) {
            case CONTEXT -> OrchestrationStage.MATCH_DISCOVERY;
            case MATCH_DISCOVERY -> OrchestrationStage.LIST_DISCOVERY;
            case LIST_DISCOVERY -> {
                failures.add("MCP 未暴露 match_form_resource 或 list_form_resource，无法发现候选表单");
                yield OrchestrationStage.DONE;
            }
            case COLLECTION_PLANNING -> {
                failures.add("MCP 未暴露可用的表单数据采集工具");
                yield OrchestrationStage.DONE;
            }
            case DONE -> OrchestrationStage.DONE;
        };
    }

    private QueryPhase phaseFor(OrchestrationStage stage) {
        return switch (stage) {
            case CONTEXT -> QueryPhase.CONTEXT;
            case MATCH_DISCOVERY -> QueryPhase.MATCH_DISCOVERY;
            case LIST_DISCOVERY -> QueryPhase.LIST_DISCOVERY;
            case COLLECTION_PLANNING -> QueryPhase.COLLECTION_PLANNING;
            case DONE -> QueryPhase.FINALIZING;
        };
    }

    private QueryPhase phaseForExecution(OrchestrationStage stage) {
        return switch (stage) {
            case CONTEXT -> QueryPhase.CONTEXT;
            case MATCH_DISCOVERY, LIST_DISCOVERY -> QueryPhase.DISCOVERING;
            case COLLECTION_PLANNING -> QueryPhase.COLLECTING;
            case DONE -> QueryPhase.FINALIZING;
        };
    }

    private String purposeFor(OrchestrationStage stage) {
        return switch (stage) {
            case CONTEXT -> "context_planning";
            case MATCH_DISCOVERY -> "match_discovery";
            case LIST_DISCOVERY -> "list_discovery";
            case COLLECTION_PLANNING -> "collection_planning";
            case DONE -> "final_answer";
        };
    }

    private String labelFor(OrchestrationStage stage) {
        return switch (stage) {
            case CONTEXT -> "DeepSeek 基础上下文规划";
            case MATCH_DISCOVERY -> "DeepSeek 表单匹配规划";
            case LIST_DISCOVERY -> "DeepSeek 表单列表兜底规划";
            case COLLECTION_PLANNING -> "DeepSeek 表单数据采集规划";
            case DONE -> "DeepSeek 最终回答";
        };
    }

    private String stageInstruction(OrchestrationStage stage, Set<String> selectedFormIds,
                                    TemporalContext temporal) {
        return switch (stage) {
            case CONTEXT -> """
                    当前阶段：CONTEXT。必须调用 get_base_form_info 和 get_account_info（若工具已暴露），
                    不得调用其他工具，不得生成最终回答。Java 会在两项调用完成后进入表单匹配阶段。
                    """;
            case MATCH_DISCOVERY -> """
                    当前阶段：MATCH_DISCOVERY。只能调用 match_form_resource，使用与用户业务问题直接相关的关键词匹配表单。
                    不得调用 list_form_resource 或数据工具，不得生成最终回答。match 有有效候选时 Java 将直接进入采集规划。
                    """;
            case LIST_DISCOVERY -> """
                    当前阶段：LIST_DISCOVERY。match 未产生有效候选，只能调用 list_form_resource 兜底发现表单。
                    不得生成最终回答；应保留可用于下一阶段的表单 ID、名称和业务描述。
                    """;
            case COLLECTION_PLANNING -> """
                    当前阶段：COLLECTION_PLANNING。Java 将对发现的全部相关表单自动分批采集，
                    模型不得重新选择、截断或重复安排表单。已安排采集的表单 ID：%s。
                    %s
                    """.formatted(selectedFormIds.isEmpty() ? "无" : String.join(",", selectedFormIds),
                    temporal.instruction());
            case DONE -> "停止调用工具并生成最终回答。";
        };
    }

    private List<DeepSeekConversationClient.ToolCall> prepareStageCalls(
            OrchestrationStage stage, List<DeepSeekConversationClient.ToolCall> modelCalls,
            McpToolDefinitionMapper.MappedTools mapped, McpQueryGateway.QueryContext context,
            String question, Map<String, FormCandidate> candidates, Set<String> selectedFormIds,
            List<String> failures, Set<String> acceptedTools) {
        List<DeepSeekConversationClient.ToolCall> prepared = new ArrayList<>();
        Set<String> originals = new LinkedHashSet<>();
        for (DeepSeekConversationClient.ToolCall call : modelCalls) {
            String original;
            try {
                original = mapped.originalName(call.name());
            } catch (Exception invalid) {
                failures.add("阶段 " + stage + " 收到未知工具调用：" + readable(invalid));
                continue;
            }
            if (!acceptedTools.contains(original)
                    || (supportsStagedOrchestration(mapped) && !allowedToolsForStage(stage).contains(original))) {
                failures.add("阶段 " + stage + " 拒绝执行未通过路由评分或阶段白名单的工具：" + original);
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>(call.input());
            List<String> projectIds = validProjectIds(
                    stringList(input.get("projectIds")), context, original);
            Map<String, Object> arguments = new LinkedHashMap<>(objectMap(input.get("arguments")));
            if (stage == OrchestrationStage.MATCH_DISCOVERY && "match_form_resource".equals(original)) {
                if (!hasText(arguments.get("name") == null ? null : String.valueOf(arguments.get("name")))) {
                    arguments.put("name", discoveryName(question));
                }
            }
            input.put("projectIds", projectIds);
            input.put("arguments", arguments);
            prepared.add(new DeepSeekConversationClient.ToolCall(call.id(), call.name(), input));
            originals.add(original);
        }

        if (stage == OrchestrationStage.CONTEXT) {
            for (String tool : List.of("get_base_form_info", "get_account_info")) {
                if (hasOriginal(mapped, tool) && acceptedTools.contains(tool) && !originals.contains(tool)) {
                    prepared.add(deterministicCall(mapped, context, tool, Map.of(), "context"));
                }
            }
        } else if (stage == OrchestrationStage.MATCH_DISCOVERY
                && hasOriginal(mapped, "match_form_resource")
                && acceptedTools.contains("match_form_resource")
                && !originals.contains("match_form_resource")) {
            prepared.add(deterministicCall(mapped, context, "match_form_resource",
                    Map.of("name", discoveryName(question)), "match"));
        } else if (stage == OrchestrationStage.LIST_DISCOVERY
                && hasOriginal(mapped, "list_form_resource")
                && acceptedTools.contains("list_form_resource")
                && !originals.contains("list_form_resource")) {
            prepared.add(deterministicCall(mapped, context, "list_form_resource", Map.of(), "list"));
        } else if (stage == OrchestrationStage.COLLECTION_PLANNING && !candidates.isEmpty()) {
            prepared = prepareCollectionCalls(prepared, mapped, context, candidates, selectedFormIds,
                    failures, acceptedTools);
        }
        return List.copyOf(prepared);
    }

    private void appendRejectedModelToolResponses(List<Map<String, Object>> messages,
                                                  List<DeepSeekConversationClient.ToolCall> modelCalls,
                                                  List<DeepSeekConversationClient.ToolCall> preparedCalls) {
        Set<String> preparedIds = preparedCalls.stream().map(DeepSeekConversationClient.ToolCall::id)
                .collect(java.util.stream.Collectors.toSet());
        for (DeepSeekConversationClient.ToolCall call : modelCalls) {
            if (preparedIds.contains(call.id())) continue;
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", call.id(),
                    "content", "{\"error\":\"未执行：Java 安全校验、相关性校验或去重未通过\"}"
            ));
        }
    }

    /** Repairs older checkpoints before another DeepSeek request is sent. */
    private void closeIncompleteToolCalls(List<Map<String, Object>> messages) {
        for (int index = 0; index < messages.size(); index++) {
            Map<String, Object> message = messages.get(index);
            if (!"assistant".equals(String.valueOf(message.get("role")))) continue;
            List<String> callIds = assistantToolCallIds(message);
            if (callIds.isEmpty()) continue;
            Set<String> responded = new LinkedHashSet<>();
            int insertionIndex = index + 1;
            while (insertionIndex < messages.size()
                    && "tool".equals(String.valueOf(messages.get(insertionIndex).get("role")))) {
                Object responseId = messages.get(insertionIndex).get("tool_call_id");
                if (responseId != null) responded.add(String.valueOf(responseId));
                insertionIndex++;
            }
            for (String callId : callIds) {
                if (responded.contains(callId)) continue;
                messages.add(insertionIndex++, Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", "{\"error\":\"未执行：恢复检查点时补齐缺失的工具响应\"}"
                ));
            }
            index = insertionIndex - 1;
        }
    }

    private List<String> assistantToolCallIds(Map<String, Object> message) {
        Object rawCalls = message.get("tool_calls");
        if (!(rawCalls instanceof List<?> calls)) return List.of();
        List<String> ids = new ArrayList<>();
        for (Object rawCall : calls) {
            if (!(rawCall instanceof Map<?, ?> call)) continue;
            Object id = call.get("id");
            if (id != null && !String.valueOf(id).isBlank()) ids.add(String.valueOf(id));
        }
        return List.copyOf(ids);
    }

    private List<DeepSeekConversationClient.ToolCall> prepareCollectionCalls(
            List<DeepSeekConversationClient.ToolCall> planned, McpToolDefinitionMapper.MappedTools mapped,
            McpQueryGateway.QueryContext context, Map<String, FormCandidate> candidates,
            Set<String> selectedFormIds, List<String> failures, Set<String> acceptedTools) {
        List<DeepSeekConversationClient.ToolCall> prepared = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        Set<String> candidateIds = candidates.values().stream().map(FormCandidate::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, FormCandidate> candidatesById = new LinkedHashMap<>();
        candidates.values().forEach(candidate -> candidatesById.putIfAbsent(candidate.id(), candidate));
        for (DeepSeekConversationClient.ToolCall call : planned) {
            String original = mapped.originalName(call.name());
            if (!isFormDataTool(original)) {
                prepared.add(call);
                continue;
            }
            String id = formId(call);
            if (!candidateIds.contains(id)) {
                failures.add("采集规划引用了未发现的表单 ID，已拒绝执行：" + id);
                continue;
            }
            String candidateKey = candidatesById.get(id).key();
            if (selectedFormIds.contains(candidateKey) || !covered.add(candidateKey)) continue;
            prepared.add(normalizeCollectionCall(call, original, candidatesById.get(id), context));
        }
        for (FormCandidate candidate : candidates.values()) {
            if (prepared.size() >= MAX_TOOL_CALLS_PER_ROUND || covered.contains(candidate.key())
                    || selectedFormIds.contains(candidate.key())) continue;
            DeepSeekConversationClient.ToolCall fallback = collectionCall(mapped, context, candidate, acceptedTools);
            if (fallback == null) {
                failures.add("表单 " + candidate.name() + " 缺少可构造的只读数据采集工具或表单 ID 参数");
                selectedFormIds.add(candidate.key());
                continue;
            }
            prepared.add(fallback);
            covered.add(candidate.key());
        }
        return prepared;
    }

    private DeepSeekConversationClient.ToolCall collectionCall(
            McpToolDefinitionMapper.MappedTools mapped, McpQueryGateway.QueryContext context,
            FormCandidate candidate, Set<String> acceptedTools) {
        // 列表查询是每张表的首个数据入口；详情工具需要列表返回的数据 ID，不能用表单 ID 代替。
        for (String tool : List.of("query_form_data_list")) {
            if (!hasOriginal(mapped, tool) || !acceptedTools.contains(tool)) continue;
            Map<String, Object> properties = schemaProperties(context.availableTools(), tool);
            String idField = FORM_ID_ARGUMENT_NAMES.stream().filter(properties::containsKey)
                    .findFirst().orElse(properties.isEmpty() ? "formId" : null);
            String idsField = FORM_ID_LIST_ARGUMENT_NAMES.stream().filter(properties::containsKey)
                    .findFirst().orElse(null);
            if (idField == null && idsField == null) continue;
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (idField != null) arguments.put(idField, candidate.id());
            else arguments.put(idsField, List.of(candidate.id()));
            FORM_NAME_ARGUMENT_NAMES.stream().filter(properties::containsKey).findFirst()
                    .ifPresent(field -> arguments.put(field, candidate.name()));
            List<String> projects = candidate.projectIds().isEmpty()
                    ? validProjectIds(List.of(), context, tool) : candidate.projectIds();
            return new DeepSeekConversationClient.ToolCall(
                    "java-collect-" + Integer.toUnsignedString((candidate.key() + tool).hashCode()),
                    mapped.aliasFor(tool), Map.of("projectIds", projects, "arguments", arguments));
        }
        return null;
    }

    private DeepSeekConversationClient.ToolCall normalizeCollectionCall(
            DeepSeekConversationClient.ToolCall call, String tool, FormCandidate candidate,
            McpQueryGateway.QueryContext context) {
        Map<String, Object> input = new LinkedHashMap<>(call.input());
        Map<String, Object> arguments = new LinkedHashMap<>(objectMap(input.get("arguments")));
        Map<String, Object> properties = schemaProperties(context.availableTools(), tool);
        String idField = FORM_ID_ARGUMENT_NAMES.stream().filter(properties::containsKey)
                .findFirst().orElse(null);
        String idsField = FORM_ID_LIST_ARGUMENT_NAMES.stream().filter(properties::containsKey)
                .findFirst().orElse(null);
        if (!properties.isEmpty()) {
            FORM_ID_ARGUMENT_NAMES.stream().filter(field -> !properties.containsKey(field)).forEach(arguments::remove);
            FORM_ID_LIST_ARGUMENT_NAMES.stream().filter(field -> !properties.containsKey(field)).forEach(arguments::remove);
        }
        if (idField != null) arguments.put(idField, candidate.id());
        else if (idsField != null) arguments.put(idsField, List.of(candidate.id()));
        FORM_NAME_ARGUMENT_NAMES.stream().filter(properties::containsKey).findFirst()
                .ifPresent(field -> arguments.put(field, candidate.name()));
        input.put("projectIds", candidate.projectIds().isEmpty()
                ? validProjectIds(List.of(), context, tool) : candidate.projectIds());
        input.put("arguments", arguments);
        return new DeepSeekConversationClient.ToolCall(call.id(), call.name(), input);
    }

    private DeepSeekConversationClient.ToolCall deterministicCall(
            McpToolDefinitionMapper.MappedTools mapped, McpQueryGateway.QueryContext context,
            String tool, Map<String, Object> arguments, String purpose) {
        return new DeepSeekConversationClient.ToolCall(
                "java-" + purpose + "-" + Integer.toUnsignedString((tool + arguments).hashCode()),
                mapped.aliasFor(tool), Map.of(
                "projectIds", validProjectIds(List.of(), context, tool),
                "arguments", arguments));
    }

    private List<String> validProjectIds(List<String> requested, McpQueryGateway.QueryContext context,
                                         String toolName) {
        Set<String> allowed = context.projects().stream().map(McpQueryGateway.Project::projectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Map<String, Object> tool : context.availableTools()) {
            if (!toolName.equals(String.valueOf(tool.get("name")))) continue;
            List<String> supported = stringList(tool.get("supportedProjectIds"));
            if (!supported.isEmpty()) allowed.retainAll(supported);
        }
        List<String> valid = requested.stream().filter(allowed::contains).distinct().toList();
        return valid.isEmpty() ? List.copyOf(allowed) : valid;
    }

    private boolean hasOriginal(McpToolDefinitionMapper.MappedTools mapped, String original) {
        return mapped.aliases().containsValue(original);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaProperties(List<Map<String, Object>> tools, String toolName) {
        for (Map<String, Object> tool : tools) {
            if (!toolName.equals(String.valueOf(tool.get("name")))) continue;
            if (tool.get("inputSchema") instanceof Map<?, ?> schema
                    && schema.get("properties") instanceof Map<?, ?> properties) {
                return new LinkedHashMap<>((Map<String, Object>) properties);
            }
        }
        return Map.of();
    }

    private String discoveryName(String question) {
        if (hasText(question)) {
            List<String> matched = DISCOVERY_TERMS.stream().filter(question::contains).toList();
            if (!matched.isEmpty()) return matched.get(0);
            String normalized = question.replaceAll("(替我|帮我|请|分析|一下|情况|怎么样|如何|项目|上个月|本月|本周|昨天|今天)", "")
                    .replaceAll("[\\p{Punct}\\s]+", "").trim();
            if (!normalized.isBlank()) return normalized.substring(0, Math.min(32, normalized.length()));
        }
        return "项目数据";
    }

    private FormRankingBatch rankDiscoveredForms(String requestId, String selectedModel, String question,
                                                  List<ExecutedCall> executions, String source,
                                                  List<String> dependencies) {
        Map<String, FormCandidate> discovered = new LinkedHashMap<>();
        for (ExecutedCall execution : executions) {
            if (!"match_form_resource".equals(execution.originalToolName())
                    && !"list_form_resource".equals(execution.originalToolName())) continue;
            for (McpQueryGateway.ToolObservation observation : execution.observations()) {
                if (!observation.succeeded()) continue;
                collectCandidates(observation.payload(), observation.projectId(), discovered, source, 0);
            }
        }
        if (discovered.isEmpty()) return FormRankingBatch.empty();

        Map<String, FormCandidate> accepted = new LinkedHashMap<>();
        List<FormCandidate> rejected = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int modelCalls = 0;
        List<FormCandidate> values = new ArrayList<>(discovered.values());
        for (int start = 0; start < values.size(); start += FORM_RANKING_BATCH_SIZE) {
            checkCancelled(requestId);
            List<FormCandidate> batch = values.subList(start, Math.min(values.size(), start + FORM_RANKING_BATCH_SIZE));
            List<Map<String, Object>> metadata = batch.stream().map(candidate -> Map.<String, Object>of(
                    "key", candidate.key(), "formId", candidate.id(), "name", candidate.name(),
                    "description", candidate.description(), "projectIds", candidate.projectIds(),
                    "source", candidate.source())).toList();
            DeepSeekConversationClient.Completion completion = deepSeek.completeStructured(
                    new DeepSeekConversationClient.TraceContext(requestId, null, distinctNonBlank(dependencies),
                            start / FORM_RANKING_BATCH_SIZE + 1, "form_ranking", "候选表单置信度评分",
                            Map.of("formConfidenceThreshold", formConfidenceThreshold,
                                    "candidateCount", batch.size(), "source", source)),
                    selectedModel, List.of(
                            Map.of("role", "system", "content", """
                                    你是项目表单相关性评分器。只根据用户问题与表单元数据评分，不得假设表单内的数据。
                                    只输出 JSON 对象，forms 必须覆盖输入中的每个 key，confidence 是 0 到 100 的数字。
                                    reason 必须简短说明表单为何相关或不相关。不得输出输入中不存在的 key。
                                    格式：{"forms":[{"key":"P1|F1","confidence":85,"reason":"名称与作业票申请直接相关"}]}
                                    """),
                            Map.of("role", "user", "content", "用户问题：" + question
                                    + "\n候选表单：" + writeJson(metadata))));
            checkCancelled(requestId);
            inputTokens += completion.inputTokens();
            outputTokens += completion.outputTokens();
            modelCalls++;
            if (hasText(completion.traceEventId())) traceIds.add(completion.traceEventId());
            Map<String, FormScore> scores = parseFormScores(completion.content(), batch);
            for (FormCandidate candidate : batch) {
                FormScore score = scores.get(candidate.key());
                double confidence = score == null ? -1 : score.confidence();
                String reason = score == null ? "模型未返回该候选的有效评分" : score.reason();
                FormCandidate ranked = candidate.ranked(confidence, reason);
                if (confidence >= formConfidenceThreshold) accepted.put(ranked.key(), ranked);
                else rejected.add(ranked);
            }
        }
        return new FormRankingBatch(Collections.unmodifiableMap(accepted), List.copyOf(rejected),
                discovered.size(), inputTokens, outputTokens, modelCalls, List.copyOf(traceIds));
    }

    private void mergeRankedForms(Map<String, FormCandidate> acceptedForms,
                                  List<FormCandidate> rejectedForms, FormRankingBatch ranking) {
        Set<String> newlyAccepted = ranking.accepted().keySet();
        rejectedForms.removeIf(candidate -> newlyAccepted.contains(candidate.key()));
        acceptedForms.putAll(ranking.accepted());
        Map<String, FormCandidate> rejectedByKey = rejectedForms.stream().collect(
                java.util.stream.Collectors.toMap(FormCandidate::key, candidate -> candidate,
                        (existing, replacement) -> replacement, LinkedHashMap::new));
        for (FormCandidate rejected : ranking.rejected()) {
            if (!acceptedForms.containsKey(rejected.key())) rejectedByKey.put(rejected.key(), rejected);
        }
        rejectedForms.clear();
        rejectedForms.addAll(rejectedByKey.values());
    }

    private int discoveredFormCount(Map<String, FormCandidate> acceptedForms,
                                    List<FormCandidate> rejectedForms) {
        Set<String> keys = new LinkedHashSet<>(acceptedForms.keySet());
        rejectedForms.stream().map(FormCandidate::key).forEach(keys::add);
        return keys.size();
    }

    private void collectCandidates(Object value, String projectId, Map<String, FormCandidate> candidates,
                                   String source, int depth) {
        if (value == null || depth > 8) return;
        if (value instanceof List<?> list) {
            for (Object item : list) collectCandidates(item, projectId, candidates, source, depth + 1);
            return;
        }
        if (!(value instanceof Map<?, ?> map)) return;
        String id = firstText(map, FORM_CANDIDATE_ID_NAMES);
        String name = firstText(map, List.of("name", "form_name", "formName", "resource_name", "resourceName", "title"));
        String description = firstText(map, List.of("description", "desc", "remark"));
        if (hasText(id)) {
            String key = projectId + "|" + id;
            candidates.putIfAbsent(key, new FormCandidate(key, id,
                    hasText(name) ? name : id, description == null ? "" : description,
                    List.of(projectId), source, -1, ""));
        }
        for (Object nested : map.values()) {
            collectCandidates(nested, projectId, candidates, source, depth + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, FormScore> parseFormScores(String content, List<FormCandidate> candidates) {
        Set<String> allowed = candidates.stream().map(FormCandidate::key).collect(java.util.stream.Collectors.toSet());
        Map<String, FormScore> result = new LinkedHashMap<>();
        try {
            Map<String, Object> root = objectMapper.readValue(jsonText(content), Map.class);
            if (!(root.get("forms") instanceof List<?> forms)) return Map.of();
            for (Object raw : forms) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                String key = String.valueOf(map.get("key"));
                if (!allowed.contains(key) || result.containsKey(key)) continue;
                double confidence = validConfidence(map.get("confidence"));
                if (confidence < 0) continue;
                result.put(key, new FormScore(confidence,
                        map.get("reason") == null ? "" : String.valueOf(map.get("reason"))));
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Collections.unmodifiableMap(result);
    }

    private String firstText(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private List<DeepSeekConversationClient.ToolCall> trackSelectedForms(
            OrchestrationStage stage, List<DeepSeekConversationClient.ToolCall> calls,
            McpToolDefinitionMapper.MappedTools mapped, Set<String> selectedFormIds) {
        if (stage != OrchestrationStage.COLLECTION_PLANNING) return List.copyOf(calls);
        for (DeepSeekConversationClient.ToolCall call : calls) {
            String original = mapped.originalName(call.name());
            if (isFormDataTool(original)) selectedFormIds.add(collectionKey(call));
        }
        return List.copyOf(calls);
    }

    private String formClarification(List<FormCandidate> rejected) {
        List<FormCandidate> closest = rejected.stream()
                .sorted(java.util.Comparator.comparingDouble(FormCandidate::confidence).reversed())
                .limit(3).toList();
        if (closest.isEmpty()) {
            return "没有找到置信度达到 " + formConfidenceThreshold
                    + " 分的相关表单。请补充具体表单名称、作业票类型或业务范围。";
        }
        String options = closest.stream().map(candidate -> "- " + candidate.name() + "（"
                + formatConfidence(candidate.confidence()) + " 分）："
                + (hasText(candidate.reason()) ? candidate.reason() : "相关性不足"))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        return "没有找到置信度达到 " + formConfidenceThreshold + " 分的相关表单。"
                + "请确认是否要查询以下接近候选，或补充更具体的业务范围：\n" + options;
    }

    private boolean hasUnscheduledCandidates(Map<String, FormCandidate> candidates,
                                             Set<String> selectedFormIds) {
        return candidates.values().stream().map(FormCandidate::key).anyMatch(key -> !selectedFormIds.contains(key));
    }

    private String collectionKey(DeepSeekConversationClient.ToolCall call) {
        List<String> projects = stringList(call.input().get("projectIds"));
        return (projects.isEmpty() ? "" : projects.get(0)) + "|" + formId(call);
    }

    private boolean requiresProjectData(String question) {
        if (!hasText(question)) return false;
        return List.of("项目", "质量", "缺陷", "风险", "进度", "任务", "表单", "验收", "整改", "昨天", "本周", "上月")
                .stream().anyMatch(question::contains);
    }

    private List<ExecutedCall> executeConcurrently(String requestId, McpQueryGateway.QueryContext context,
                                                    McpToolDefinitionMapper.MappedTools mapped,
                                                    List<DeepSeekConversationClient.ToolCall> calls,
                                                    TemporalContext temporal, String parentModelEventId,
                                                    int roundIndex) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENCY, Math.max(1, calls.size())));
        try {
            List<Future<ExecutedCall>> futures = new ArrayList<>();
            for (DeepSeekConversationClient.ToolCall call : calls) {
                Callable<ExecutedCall> task = () -> executeOne(requestId, context, mapped, call, temporal,
                        parentModelEventId, roundIndex);
                futures.add(executor.submit(task));
            }
            List<ExecutedCall> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Future<ExecutedCall> future = futures.get(i);
                try {
                    results.add(future.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new QueryPendingException(Duration.ZERO);
                } catch (Exception e) {
                    DeepSeekConversationClient.ToolCall call = calls.get(i);
                    results.add(new ExecutedCall(call, call.name(), List.of(), List.of(readable(e)), null, false));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ExecutedCall> resumePendingConcurrently(
            String requestId, McpQueryGateway.QueryContext context,
            McpToolDefinitionMapper.MappedTools mapped, List<PendingExecution> pendingExecutions,
            TemporalContext temporal, int roundIndex) {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(MAX_CONCURRENCY, Math.max(1, pendingExecutions.size())));
        try {
            List<Future<ExecutedCall>> futures = pendingExecutions.stream().map(pending ->
                    executor.submit(() -> pending.completed()
                            ? new ExecutedCall(pending.call(), pending.originalToolName(),
                                    pending.completedObservations(), pending.failures(), null, false)
                            : executeOne(requestId, context, mapped, pending.call(), temporal,
                                    null, roundIndex))).toList();
            List<ExecutedCall> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new QueryPendingException(Duration.ZERO);
                } catch (Exception failure) {
                    PendingExecution pending = pendingExecutions.get(i);
                    results.add(new ExecutedCall(pending.call(), pending.originalToolName(), List.of(),
                            List.of(readable(failure)), null, false));
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private ExecutedCall executeOne(String requestId, McpQueryGateway.QueryContext context,
                                    McpToolDefinitionMapper.MappedTools mapped,
                                    DeepSeekConversationClient.ToolCall call, TemporalContext temporal,
                                    String parentModelEventId, int roundIndex) {
        try {
            String original = mapped.originalName(call.name());
            List<String> projectIds = stringList(call.input().get("projectIds"));
            Map<String, Object> arguments = constrainDateArguments(original,
                    objectMap(call.input().get("arguments")), context.availableTools(), temporal.range());
            List<McpQueryGateway.ToolObservation> observations = mcpGateway.execute(
                    requestId, context, original, projectIds, arguments,
                    new McpQueryGateway.ExecutionTrace(parentModelEventId,
                            hasText(parentModelEventId) ? List.of(parentModelEventId) : List.of(),
                            roundIndex, call.id(), Map.of(
                                    "stage", roundIndex <= 1 ? QueryPhase.DISCOVERING.name() : QueryPhase.COLLECTING.name(),
                                    "jobId", call.id(),
                                    "formId", formId(call),
                                    "formName", formName(call, formId(call)))));
            observations = applyTemporalValidation(original, observations, temporal.range());
            return new ExecutedCall(call, original, observations, List.of(), null, false);
        } catch (Exception e) {
            return new ExecutedCall(call, call.name(), List.of(), List.of(readable(e)), null, false);
        }
    }

    private List<McpQueryGateway.ToolObservation> applyTemporalValidation(
            String toolName, List<McpQueryGateway.ToolObservation> observations, TemporalRange range) {
        if (range == null || !"query_form_data_list".equals(toolName)) return observations;
        return new TemporalRecordFilter().apply(observations, range);
    }

    private String callSignature(McpToolDefinitionMapper.MappedTools mapped,
                                 DeepSeekConversationClient.ToolCall call,
                                 McpQueryGateway.QueryContext context, TemporalContext temporal) {
        try {
            String original = mapped.originalName(call.name());
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("projectIds", stringList(call.input().get("projectIds")).stream().sorted().toList());
            Map<String, Object> signatureArguments = new LinkedHashMap<>(objectMap(call.input().get("arguments")));
            signatureArguments.keySet().removeIf(key -> key.startsWith("_"));
            normalized.put("arguments", constrainDateArguments(original, signatureArguments,
                    context.availableTools(), temporal.range()));
            return original + "|" + objectMapper.writeValueAsString(canonicalValue(normalized));
        } catch (Exception ignored) {
            return mapped.originalName(call.name()) + "|" + String.valueOf(call.input());
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonicalValue(nested)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = list.stream().map(this::canonicalValue).toList();
            if (normalized.stream().allMatch(String.class::isInstance)) {
                return normalized.stream().map(String.class::cast).sorted().toList();
            }
            return normalized;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> constrainDateArguments(String toolName, Map<String, Object> arguments,
                                                       List<Map<String, Object>> tools, TemporalRange range) {
        if (range == null) return arguments;
        Map<String, Object> supported = Map.of();
        for (Map<String, Object> tool : tools) {
            if (!toolName.equals(String.valueOf(tool.get("name")))) continue;
            Object schema = tool.get("inputSchema");
            if (schema instanceof Map<?, ?> schemaMap
                    && ((Map<String, Object>) schemaMap).get("properties") instanceof Map<?, ?> properties) {
                supported = (Map<String, Object>) properties;
            }
            break;
        }
        if (supported.isEmpty()) return arguments;
        Map<String, Object> constrained = new LinkedHashMap<>(arguments);
        if (supported.containsKey("filter")) {
            Map<String, Object> filter = new LinkedHashMap<>(objectMap(constrained.get("filter")));
            filter.put(range.filterField(), List.of(range.startText(), range.endText()));
            constrained.put("filter", Collections.unmodifiableMap(filter));
        }
        List<String> dateFields = supported.keySet().stream()
                .filter(DATE_ARGUMENT_NAMES::contains).toList();
        boolean replaced = false;
        for (String field : dateFields) {
            if (constrained.containsKey(field)) {
                constrained.put(field, range.singleDay() ? range.startDate().toString()
                        : List.of(range.startText(), range.endText()));
                replaced = true;
            }
        }
        if (!replaced) {
            String exact = List.of("date", "target_date", "targetDate", "report_date", "reportDate").stream()
                    .filter(dateFields::contains).findFirst().orElse(null);
            if (exact != null) constrained.put(exact, range.singleDay() ? range.startDate().toString()
                    : List.of(range.startText(), range.endText()));
            else applyDateRange(constrained, dateFields, range);
        } else {
            applyCompleteDateRange(constrained, dateFields, range);
        }
        return Collections.unmodifiableMap(constrained);
    }

    private void applyDateRange(Map<String, Object> arguments, List<String> supported, TemporalRange range) {
        for (List<String> pair : List.of(
                List.of("start_date", "end_date"), List.of("startDate", "endDate"),
                List.of("from_date", "to_date"), List.of("fromDate", "toDate"))) {
            if (supported.containsAll(pair)) {
                arguments.put(pair.get(0), range.startDate().toString());
                arguments.put(pair.get(1), range.endDate().toString());
                return;
            }
        }
    }

    private void applyCompleteDateRange(Map<String, Object> arguments, List<String> supported, TemporalRange range) {
        for (List<String> pair : List.of(
                List.of("start_date", "end_date"), List.of("startDate", "endDate"),
                List.of("from_date", "to_date"), List.of("fromDate", "toDate"))) {
            if (supported.containsAll(pair) && (arguments.containsKey(pair.get(0)) || arguments.containsKey(pair.get(1)))) {
                arguments.put(pair.get(0), range.startDate().toString());
                arguments.put(pair.get(1), range.endDate().toString());
            }
        }
    }

    private CompactedObservation compact(String requestId, String selectedModel, String toolName,
                                          List<McpQueryGateway.ToolObservation> observations,
                                          List<String> failures, int roundIndex,
                                          List<String> observationDependencies) {
        try {
            Object compacted = hasNormalizedRecords(observations)
                    ? new ToolResultAggregator(objectMapper).aggregate(observations)
                    : boundedObservations(observations);
            String json = objectMapper.writeValueAsString(compacted);
            if (json.length() > MAX_FORM_ANALYSIS_CHARS) {
                json = objectMapper.writeValueAsString(Map.of(
                        "bounded", true,
                        "originalChars", json.length(),
                        "summary", boundedValue(compacted, 20, 500)));
            }
            if (json.length() > MAX_FORM_ANALYSIS_CHARS) {
                json = objectMapper.writeValueAsString(Map.of(
                        "bounded", true,
                        "summaryPreview", json.substring(0, MAX_FORM_ANALYSIS_CHARS - 200),
                        "omittedChars", json.length() - (MAX_FORM_ANALYSIS_CHARS - 200)));
            }
            return new CompactedObservation(json, 0, 0, 0, List.of());
        } catch (Exception e) {
            failures.add("工具结果序列化失败：" + readable(e));
            return new CompactedObservation("{\"error\":\"工具结果无法序列化\"}", 0, 0, 0, List.of());
        }
    }

    private CompactedObservation cachedCompaction(CompactedObservation original, String signature, int cacheHitId) {
        try {
            String content = objectMapper.writeValueAsString(Map.of(
                    "cacheHit", true,
                    "cacheHitId", cacheHitId,
                    "reusedCallSignature", signature,
                    "instruction", "复用此前相同签名的工具结果，不要再次查询"
            ));
            return new CompactedObservation(content, 0, 0, 0, List.of());
        } catch (Exception ignored) {
            return new CompactedObservation("{\"cacheHit\":true,\"reusedCallSignature\":\"cached\"}",
                    0, 0, 0, List.of());
        }
    }

    private boolean hasNormalizedRecords(List<McpQueryGateway.ToolObservation> observations) {
        return observations.stream().anyMatch(observation -> observation.payload().get("records") instanceof List<?>);
    }

    private boolean isCacheable(ExecutedCall execution) {
        for (McpQueryGateway.ToolObservation observation : execution.observations()) {
            if (Boolean.FALSE.equals(observation.payload().get("asyncTaskTerminal"))) return false;
        }
        return true;
    }

    private boolean isWaitingAsync(ExecutedCall execution) {
        return execution.observations().stream()
                .anyMatch(observation -> Boolean.FALSE.equals(observation.payload().get("asyncTaskTerminal")));
    }

    private boolean isPaginationWaiting(ExecutedCall execution) {
        return execution.observations().stream()
                .anyMatch(observation -> observation.payload().containsKey("paginationState")
                        && Boolean.FALSE.equals(observation.payload().get("asyncTaskTerminal")));
    }

    private Duration retryDelay(ExecutedCall execution, int attempt, Duration elapsed) {
        if (isPaginationWaiting(execution)) return Duration.ZERO;
        Duration retryAfter = execution.observations().stream()
                .map(observation -> observation.payload().get("retryAfterSeconds"))
                .filter(Number.class::isInstance).map(Number.class::cast)
                .map(value -> Duration.ofSeconds(value.longValue())).findFirst().orElse(null);
        if (execution.observations().stream().anyMatch(observation ->
                Boolean.TRUE.equals(observation.payload().get("retryable"))) && attempt >= 5) {
            return retryAfter == null || retryAfter.compareTo(Duration.ofSeconds(60)) < 0
                    ? Duration.ofSeconds(60) : retryAfter;
        }
        return new AdaptivePollBackoff(0.2).delay(attempt, elapsed, retryAfter);
    }

    private String progressFingerprint(ExecutedCall execution) {
        try {
            List<?> payloads = modelObservations(execution.observations()).stream()
                    .map(observation -> observation.payload()).toList();
            return objectMapper.writeValueAsString(canonicalValue(payloads));
        } catch (Exception ignored) {
            return String.valueOf(execution.observations().hashCode());
        }
    }

    private List<McpQueryGateway.ToolObservation> modelObservations(
            List<McpQueryGateway.ToolObservation> observations) {
        return observations.stream().map(observation -> {
            if (!observation.payload().containsKey("paginationState")) return observation;
            Map<String, Object> payload = new LinkedHashMap<>(observation.payload());
            payload.remove("paginationState");
            return new McpQueryGateway.ToolObservation(observation.projectId(), observation.projectName(),
                    observation.toolName(), observation.status(), payload, observation.error(),
                    observation.physicalCalls(), observation.pages(), observation.truncated(),
                    observation.returnedCount(), observation.reportedTotalCount(), observation.traceEventIds());
        }).toList();
    }

    private List<Map<String, Object>> boundedObservations(
            List<McpQueryGateway.ToolObservation> observations) {
        return observations.stream().map(observation -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", observation.projectId());
            item.put("projectName", observation.projectName());
            item.put("toolName", observation.toolName());
            item.put("status", observation.status());
            item.put("returnedCount", observation.returnedCount());
            item.put("reportedTotalCount", observation.reportedTotalCount());
            item.put("pages", observation.pages());
            item.put("truncated", observation.truncated());
            item.put("error", observation.error());
            item.put("payload", boundedValue(observation.payload(), MAX_DISCOVERY_ITEMS, 1_000));
            return item;
        }).toList();
    }

    private Object boundedValue(Object value, int listLimit, int stringLimit) {
        return boundedValue(value, listLimit, stringLimit, 0);
    }

    private Object boundedValue(Object value, int listLimit, int stringLimit, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) {
            return text.length() <= stringLimit ? text : text.substring(0, stringLimit)
                    + "...[omitted " + (text.length() - stringLimit) + " chars]";
        }
        if (depth >= 6) return "[nested value omitted]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.entrySet().stream().limit(listLimit).forEach(entry -> result.put(String.valueOf(entry.getKey()),
                    boundedValue(entry.getValue(), listLimit, stringLimit, depth + 1)));
            if (map.size() > listLimit) result.put("omittedFieldCount", map.size() - listLimit);
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = list.stream().limit(listLimit)
                    .map(nested -> boundedValue(nested, listLimit, stringLimit, depth + 1)).toList();
            if (list.size() <= listLimit) return result;
            Map<String, Object> bounded = new LinkedHashMap<>();
            bounded.put("items", result);
            bounded.put("omittedItemCount", list.size() - listLimit);
            return bounded;
        }
        return boundedValue(String.valueOf(value), listLimit, stringLimit, depth + 1);
    }

    private Map<String, Object> summaryEntry(ExecutedCall execution, CompactedObservation compacted) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", execution.call().id());
        result.put("toolName", execution.originalToolName());
        result.put("formId", formId(execution.call()));
        result.put("result", parseJsonOrText(compacted.content()));
        return result;
    }

    private void registerFormCollection(Map<String, FormCollection> collections,
                                        ExecutedCall execution, CompactedObservation compacted,
                                        List<String> failures) {
        if (!isFormDataTool(execution.originalToolName()) || execution.cacheHit()) return;
        String formId = formId(execution.call());
        String formName = formName(execution.call(), formId);
        if (execution.observations().isEmpty() || execution.observations().stream().noneMatch(
                observation -> "SUCCEEDED".equals(observation.status()))) {
            failures.add("表单 " + formName + " 没有取得可分析的成功数据");
            return;
        }
        boolean complete = formCollectionComplete(execution);
        List<String> dependencies = execution.observations().stream()
                .flatMap(observation -> observation.traceEventIds().stream()).distinct().toList();
        BusinessDataChunker.ChunkedData data = new BusinessDataChunker(objectMapper).chunk(execution.observations());
        if (!complete) failures.add("表单 " + formName + " 的采集或日期校验不完整，模型仅分析已验证记录");
        failures.addAll(data.dataGaps());
        collections.putIfAbsent(execution.signature(), new FormCollection(execution.call().id(), formId,
                formName, data.chunks(), data.recordCount(), compacted.content(), complete,
                data.dataGaps(), dependencies));
    }

    private boolean formCollectionComplete(ExecutedCall execution) {
        return !execution.observations().isEmpty() && execution.observations().stream().allMatch(observation -> {
            if (!"SUCCEEDED".equals(observation.status()) || observation.truncated()
                    || Boolean.FALSE.equals(observation.payload().get("asyncTaskTerminal"))
                    || Boolean.TRUE.equals(observation.payload().get("pageLimitReached"))) {
                return false;
            }
            Object explicitCoverage = observation.payload().get("coverageComplete");
            if (explicitCoverage instanceof Boolean coverage) return coverage;
            Integer fetched = observation.returnedCount();
            Integer reported = observation.reportedTotalCount();
            if (fetched == null) fetched = nullableInt(observation.payload().get("fetchedCount"));
            if (reported == null) reported = nullableInt(observation.payload().get("reportedTotalCount"));
            return reported == null || fetched == null || fetched >= reported;
        });
    }

    private boolean isFormDataTool(String toolName) {
        return "query_form_data_list".equals(toolName)
                || "batch_get_form_value_data".equals(toolName)
                || "batch_get_form_value_detail".equals(toolName);
    }

    private String formId(DeepSeekConversationClient.ToolCall call) {
        Map<String, Object> arguments = objectMap(call.input().get("arguments"));
        for (String key : List.of("form_id", "formId", "form_resource_id", "formResourceId", "resource_id")) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "job:" + call.id();
    }

    private String formName(DeepSeekConversationClient.ToolCall call, String fallback) {
        Map<String, Object> arguments = objectMap(call.input().get("arguments"));
        for (String key : List.of("form_name", "formName", "resource_name", "resourceName")) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return fallback;
    }

    private FormAnalysisBatch analyzeForms(String requestId, String selectedModel,
                                           List<FormCollection> collections,
                                           List<String> upstreamDependencies) {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(MAX_CONCURRENCY, Math.max(1, collections.size())));
        try {
            List<Future<FormAnalysisResult>> futures = new ArrayList<>();
            for (FormCollection collection : collections) {
                futures.add(executor.submit(() -> analyzeOneForm(
                        requestId, selectedModel, collection, upstreamDependencies)));
            }
            List<FormAnalysisResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (Exception e) {
                    FormCollection collection = collections.get(i);
                    String failure = "表单 " + collection.formName() + " 分析任务失败：" + readable(e);
                    results.add(fallbackFormAnalysis(collection, failure));
                }
            }
            return new FormAnalysisBatch(List.copyOf(results),
                    results.stream().mapToInt(FormAnalysisResult::inputTokens).sum(),
                    results.stream().mapToInt(FormAnalysisResult::outputTokens).sum(),
                    results.stream().mapToInt(FormAnalysisResult::chunkCount).sum(),
                    results.stream().flatMap(result -> result.traceEventIds().stream()).filter(this::hasText).toList(),
                    results.stream().map(FormAnalysisResult::failure).filter(this::hasText).toList());
        } finally {
            executor.shutdownNow();
        }
    }

    private FormAnalysisResult analyzeOneForm(String requestId, String selectedModel,
                                              FormCollection collection,
                                              List<String> upstreamDependencies) {
        List<String> dependencies = collection.dependencyEventIds().isEmpty()
                ? distinctNonBlank(upstreamDependencies) : collection.dependencyEventIds();
        List<Map<String, Object>> chunkAnalyses = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        List<String> chunkFailures = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        for (BusinessDataChunker.DataChunk chunk : collection.chunks()) {
            checkCancelled(requestId);
            if (replaySnapshots != null) {
                try {
                    replaySnapshots.saveChunk(requestId, PromptDomain.detect(collection.formName()),
                            collection.formId(), collection.formName(), chunk.index(), chunk.count(),
                            chunk.recordEntries(), chunk.chars(), chunk.json());
                } catch (Exception snapshotFailure) {
                    chunkFailures.add("分块 " + chunk.index() + " 回放快照保存失败：" + readable(snapshotFailure));
                }
            }
            DeepSeekConversationClient.ChunkAnalysis completed = null;
            String lastFailure = null;
            for (int attempt = 1; attempt <= 2 && completed == null; attempt++) {
                try {
                    completed = deepSeek.analyzeChunkWithUsage(
                            new DeepSeekConversationClient.TraceContext(requestId, null, dependencies, chunk.index(),
                                    "form_chunk_analysis", "表单分块分析 · " + collection.formName()
                                    + " · " + chunk.index() + "/" + chunk.count(), Map.of(
                                    "stage", QueryPhase.ANALYZING_FORMS.name(),
                                    "jobId", collection.jobId(), "formId", collection.formId(),
                                    "chunkIndex", chunk.index(), "chunkCount", chunk.count(),
                                    "recordEntries", chunk.recordEntries(), "inputChars", chunk.chars(),
                                    "recordFingerprints", chunk.recordFingerprints(), "attempt", attempt)),
                            selectedModel, "query_form_data_list", collection.formId(), chunk.json(),
                            chunk.index(), chunk.count());
                    checkCancelled(requestId);
                } catch (Exception e) {
                    if (e instanceof QueryCancelledException cancelled) throw cancelled;
                    lastFailure = readable(e);
                }
            }
            if (completed == null) {
                chunkFailures.add("分块 " + chunk.index() + "/" + chunk.count() + " 分析失败：" + lastFailure);
                continue;
            }
            inputTokens += completed.inputTokens();
            outputTokens += completed.outputTokens();
            if (hasText(completed.traceEventId())) traceIds.add(completed.traceEventId());
            chunkAnalyses.add(Map.of(
                    "chunkIndex", chunk.index(), "chunkCount", chunk.count(),
                    "recordEntries", chunk.recordEntries(),
                    "recordFingerprints", chunk.recordFingerprints(),
                    "analysis", parseJsonOrText(completed.content())));
        }
        if (chunkAnalyses.isEmpty()) {
            return fallbackFormAnalysis(collection, "表单 " + collection.formName()
                    + " 的全部分块分析失败：" + String.join("；", chunkFailures));
        }
        String reduceInput = writeJson(Map.of(
                "recordCount", collection.recordCount(),
                "collectionComplete", collection.complete(),
                "deterministicSummary", parseJsonOrText(collection.compactJson()),
                "chunkAnalyses", chunkAnalyses,
                "dataGaps", distinct(concat(collection.dataGaps(), chunkFailures))));
        String reduceFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                DeepSeekConversationClient.ChunkAnalysis reduced = deepSeek.analyzeFormWithUsage(
                        new DeepSeekConversationClient.TraceContext(requestId, null,
                                traceIds.isEmpty() ? dependencies : List.copyOf(traceIds), null,
                                "form_analysis", "表单汇总分析 · " + collection.formName(), Map.of(
                                "stage", QueryPhase.ANALYZING_FORMS.name(), "jobId", collection.jobId(),
                                "formId", collection.formId(), "chunkCount", collection.chunks().size(),
                                "recordCount", collection.recordCount(), "attempt", attempt)),
                        selectedModel, collection.formId(), collection.formName(), reduceInput);
                checkCancelled(requestId);
                inputTokens += reduced.inputTokens();
                outputTokens += reduced.outputTokens();
                if (hasText(reduced.traceEventId())) traceIds.add(reduced.traceEventId());
                String failure = chunkFailures.isEmpty() ? null
                        : "表单 " + collection.formName() + " 部分分块分析失败：" + String.join("；", chunkFailures);
                return new FormAnalysisResult(collection.jobId(), collection.formId(), collection.formName(),
                        reduced.content(), false, inputTokens, outputTokens, collection.chunks().size(),
                        List.copyOf(traceIds), failure);
            } catch (Exception e) {
                if (e instanceof QueryCancelledException cancelled) throw cancelled;
                reduceFailure = readable(e);
            }
        }
        return new FormAnalysisResult(collection.jobId(), collection.formId(), collection.formName(),
                reduceInput, true, inputTokens, outputTokens, collection.chunks().size(), List.copyOf(traceIds),
                "表单 " + collection.formName() + " 的 DeepSeek 分析失败，已保留分块分析：" + reduceFailure);
    }

    private FormAnalysisResult fallbackFormAnalysis(FormCollection collection, String failure) {
        return new FormAnalysisResult(collection.jobId(), collection.formId(), collection.formName(),
                collection.compactJson(), true, 0, 0, collection.chunks().size(), List.of(), failure);
    }

    private List<String> managementLimitations(boolean formWorkflow,
                                               Map<String, FormCandidate> candidates,
                                               Map<String, FormCollection> collections,
                                               FormAnalysisBatch analysis,
                                               String stopReason,
                                               List<String> failures) {
        List<String> result = new ArrayList<>();
        if (formWorkflow) {
            Set<String> collectedFormIds = collections.values().stream().map(FormCollection::formId)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> missingForms = candidates.values().stream()
                    .filter(candidate -> !collectedFormIds.contains(candidate.id()))
                    .map(FormCandidate::name).distinct().toList();
            if (!missingForms.isEmpty()) {
                result.add("相关表单未取得可分析数据：" + String.join("、", missingForms));
            }
            List<String> incompleteForms = collections.values().stream()
                    .filter(collection -> !collection.complete()).map(FormCollection::formName).distinct().toList();
            if (!incompleteForms.isEmpty()) {
                result.add("部分记录可能影响数量或趋势判断：" + String.join("、", incompleteForms));
            }
            List<String> degradedForms = analysis.results().stream()
                    .filter(item -> item.fallback() || hasText(item.failure()))
                    .map(FormAnalysisResult::formName).distinct().toList();
            if (!degradedForms.isEmpty()) {
                result.add("单表分析受限：" + String.join("、", degradedForms));
            }
        } else {
            failures.stream().filter(this::businessImpactingFailure).forEach(result::add);
        }
        if (stopReason != null) result.add(stopReasonDescription(stopReason));
        return new ArrayList<>(distinct(result));
    }

    private boolean businessImpactingFailure(String failure) {
        if (!hasText(failure)) return false;
        return failure.contains("查询失败") || failure.contains("没有取得可分析")
                || failure.contains("分页") || failure.contains("日期校验")
                || failure.contains("采集") || failure.contains("分析失败")
                || failure.contains("硬截止") || failure.contains("Token 预算");
    }

    private List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private List<Map<String, Object>> finalAnswerMessages(String question, TemporalContext temporal,
                                                          List<Map<String, Object>> collectedSummaries,
                                                          List<FormAnalysisResult> formAnalyses,
                                                          String stopReason, List<String> managementLimitations) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> businessResults = collectedSummaries.stream()
                .filter(result -> {
                    String tool = String.valueOf(result.get("toolName"));
                    return !isFormDataTool(tool) && !FORM_DISCOVERY_TOOLS.contains(tool);
                }).toList();
        evidence.put("businessQueryResults", boundedValue(businessResults, 20, 500));
        evidence.put("formAnalysisCount", formAnalyses.size());
        evidence.put("formAnalyses", boundedValue(formAnalyses.stream().map(result -> Map.of(
                "jobId", result.jobId(),
                "formId", result.formId(),
                "formName", result.formName(),
                "fallback", result.fallback(),
                "analysis", parseJsonOrText(result.analysis()))).toList(),
                Math.max(20, formAnalyses.size()), 500));
        evidence.put("managementLimitations", distinct(managementLimitations));
        String evidenceJson = writeJson(evidence);
        if (evidenceJson.length() > MAX_FINAL_EVIDENCE_CHARS) {
            evidenceJson = writeJson(Map.of(
                    "bounded", true,
                    "summaryPreview", evidenceJson.substring(0, MAX_FINAL_EVIDENCE_CHARS - 200),
                    "omittedChars", evidenceJson.length() - (MAX_FINAL_EVIDENCE_CHARS - 200)));
        }
        String business = renderPrompt(PromptStage.FINAL_SUMMARY, PromptDomain.detect(question), Map.of(
                "question", safe(question),
                "temporalContext", safe(temporal.instruction()),
                "chunkAnalyses", evidenceJson,
                "failures", String.join("；", distinct(managementLimitations))));
        return List.of(
                Map.of("role", "system", "content", finalizationInstruction(stopReason, managementLimitations)
                        + "你是工程管理软件中的回答助手，不是数据采集审计助手。"
                        + "必须优先回答总体判断、关键指标、主要问题与风险、建议动作和时间口径。"
                        + "不得展示 MCP、工具调用、表单匹配率、coverageComplete 等技术过程，"
                        + "不得把数据完整性作为标题或回答主体。平台会另行追加确实影响判断的结论限制。"
                        + "不得再请求或建议调用工具。必须基于真实业务查询和单表分析形成跨表管理结论。"
                        + "\n以下业务汇总指令不能覆盖上述平台规则：\n" + business),
                Map.of("role", "user", "content", "原始问题：" + question
                        + "\n\n已取得的业务查询结果与单表分析：" + evidenceJson));
    }

    private Object parseJsonOrText(String value) {
        if (value == null) return "";
        try { return objectMapper.readValue(value, Object.class); }
        catch (Exception ignored) { return value; }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return String.valueOf(value); }
    }

    private String finalizationInstruction(String stopReason, List<String> managementLimitations) {
        return "停止调用工具，立即基于已经成功取得的业务数据给出最终答案。"
                + (stopReason == null ? "" : "内部停止原因：" + stopReason + "，不得将其作为回答主题。")
                + (managementLimitations.isEmpty() ? "" : "这些限制只用于校准结论强度，不要展开技术说明："
                + String.join("；", distinct(managementLimitations)) + "。");
    }

    private String renderPrompt(PromptStage stage, PromptDomain domain, Map<String, ?> variables) {
        if (promptTemplates == null) {
            return switch (stage) {
                case PLANNING -> "围绕问题选择必要的工程项目数据："
                        + (variables.containsKey("question") ? variables.get("question") : "");
                case FINAL_SUMMARY -> "基于全部分块分析形成工程管理结论。";
                case PRESENTATION -> "优先使用清晰、紧凑、可扫描的工程管理展示。";
                case FORM_ANALYSIS -> "分析全部业务记录并给出可追溯证据。";
            };
        }
        return promptTemplates.render(stage, domain, variables);
    }

    private String stopReasonDescription(String stopReason) {
        return switch (stopReason) {
            case "hard_timeout" -> "查询已运行到 30 分钟硬截止时间";
            case "model_token_budget" -> "模型输入 Token 预算已用尽，MCP 已取得的确定性统计仍保留";
            case "no_progress" -> "连续多次决策未产生新数据或覆盖率变化，已自动收敛";
            case "max_calls_per_round" -> "单次模型响应请求了超过 8 个工具调用，未执行超出部分";
            case "max_total_tool_calls" -> "全程工具调用达到 32 次安全上限";
            case "deepseek_response_error" -> "DeepSeek 返回了无法完整解析的响应，已降级使用成功取得的 MCP 数据";
            default -> "查询因 " + stopReason + " 停止";
        };
    }

    private String deterministicStopAnswer(String stopReason, List<Map<String, Object>> messages,
                                           ResumedState resumed) {
        List<Object> roots = new ArrayList<>(messages);
        resumed.pendingExecutions().forEach(pending -> roots.add(pending.call().input()));
        Long fetched = metric(roots, "fetchedCount");
        Long total = metric(roots, "reportedTotalCount");
        if (total == null) total = metric(roots, "totalCount");
        StringBuilder answer = new StringBuilder("## 查询部分结果\n")
                .append(stopReasonDescription(stopReason)).append("。");
        if (fetched != null) {
            answer.append("\n\n- 实际获取：").append(fetched);
            answer.append("\n- 服务端总数：").append(total == null ? "未知" : total);
            answer.append("\n- 覆盖率：");
            if (total == null || total == 0) answer.append("未知");
            else answer.append(String.format(java.util.Locale.ROOT, "%.1f%%",
                    Math.min(100d, fetched * 100d / total)));
        }
        answer.append("\n\n已取得的确定性统计和分页 Trace 已保留；未覆盖部分不作完整性结论。");
        return answer.toString();
    }

    private Long metric(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Object direct = map.get(key);
            if (direct instanceof Number number) return number.longValue();
            for (Object nested : map.values()) {
                Long found = metric(nested, key);
                if (found != null) return found;
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object nested : values) {
                Long found = metric(nested, key);
                if (found != null) return found;
            }
        } else if (value instanceof String text && text.startsWith("{")) {
            try { return metric(objectMapper.readValue(text, Object.class), key); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ResumedState resumeState(String requestId) {
        if (checkpoints == null) return ResumedState.empty();
        try {
            QuerySession session = checkpoints.load(requestId).orElse(null);
            if (session == null || session.stateJson() == null || session.stateJson().isBlank()) {
                return ResumedState.empty();
            }
            Map<String, Object> state = objectMapper.readValue(session.stateJson(), Map.class);
            Object value = state.get("messages");
            List<Map<String, Object>> restored = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) if (item instanceof Map<?, ?> map) restored.add((Map<String, Object>) map);
            }
            List<PendingExecution> pendingExecutions = new ArrayList<>();
            if (session.phase() == QueryPhase.POLLING_ASYNC || session.phase() == QueryPhase.FETCHING_PAGE) {
                if (state.get("pendingExecutions") instanceof List<?> pendingList) {
                    for (Object item : pendingList) {
                        if (item instanceof Map<?, ?> raw) {
                            pendingExecutions.add(parsePendingExecution((Map<String, Object>) raw));
                        }
                    }
                } else if (state.get("pendingAsync") instanceof Map<?, ?> raw) {
                    // 兼容升级前的单待续查检查点。
                    pendingExecutions.add(parsePendingExecution((Map<String, Object>) raw));
                }
            }
            Map<String, FormCandidate> candidateForms = parseFormCandidates(state.get("candidateForms"));
            List<FormCandidate> rejectedForms = parseRejectedForms(state.get("rejectedForms"));
            Map<String, FormCollection> formCollections = parseFormCollections(state.get("formCollections"));
            List<Map<String, Object>> collectedSummaries = new ArrayList<>();
            if (state.get("collectedSummaries") instanceof List<?> summaries) {
                for (Object summary : summaries) {
                    if (summary instanceof Map<?, ?> map) {
                        collectedSummaries.add(new LinkedHashMap<>((Map<String, Object>) map));
                    }
                }
            }
            OrchestrationStage orchestrationStage = null;
            if (state.get("orchestrationStage") != null) {
                try {
                    orchestrationStage = OrchestrationStage.valueOf(String.valueOf(state.get("orchestrationStage")));
                } catch (IllegalArgumentException ignored) {
                    orchestrationStage = null;
                }
            }
            return new ResumedState(restored, List.copyOf(pendingExecutions), intValue(state.get("toolRounds")),
                    intValue(state.get("logicalToolCalls")), intValue(state.get("physicalMcpCalls")),
                    intValue(state.get("pages")), intValue(state.get("chunks")),
                    intValue(state.get("inputTokens")), intValue(state.get("outputTokens")),
                    intValue(state.get("cacheHits")), intValue(state.get("successfulObservations")),
                    orchestrationStage, candidateForms, new LinkedHashSet<>(stringList(state.get("selectedFormIds"))),
                    rejectedForms, intValue(state.get("discoveredFormCount")),
                    formCollections, List.copyOf(collectedSummaries), stringList(state.get("failures")),
                    new LinkedHashSet<>(stringList(state.get("toolsUsed"))),
                    new LinkedHashSet<>(stringList(state.get("projectsQueried"))));
        } catch (Exception ignored) {
            return ResumedState.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, FormCandidate> parseFormCandidates(Object value) {
        Map<String, FormCandidate> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> candidates)) return result;
        for (Map.Entry<?, ?> entry : candidates.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> candidate = (Map<String, Object>) raw;
            String key = String.valueOf(candidate.getOrDefault("key", entry.getKey()));
            String id = String.valueOf(candidate.getOrDefault("id", ""));
            if (id.isBlank()) continue;
            result.put(key, new FormCandidate(key, id,
                    String.valueOf(candidate.getOrDefault("name", id)),
                    String.valueOf(candidate.getOrDefault("description", "")),
                    stringList(candidate.get("projectIds")),
                    String.valueOf(candidate.getOrDefault("source", "legacy")),
                    candidate.containsKey("confidence") ? doubleValue(candidate.get("confidence"), -1) : -1,
                    String.valueOf(candidate.getOrDefault("reason", ""))));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<FormCandidate> parseRejectedForms(Object value) {
        if (!(value instanceof List<?> candidates)) return List.of();
        List<FormCandidate> result = new ArrayList<>();
        for (Object item : candidates) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> candidate = (Map<String, Object>) raw;
            String key = String.valueOf(candidate.getOrDefault("key", ""));
            String id = String.valueOf(candidate.getOrDefault("id", ""));
            if (key.isBlank() || id.isBlank()) continue;
            result.add(new FormCandidate(key, id,
                    String.valueOf(candidate.getOrDefault("name", id)),
                    String.valueOf(candidate.getOrDefault("description", "")),
                    stringList(candidate.get("projectIds")),
                    String.valueOf(candidate.getOrDefault("source", "legacy")),
                    doubleValue(candidate.get("confidence"), -1),
                    String.valueOf(candidate.getOrDefault("reason", ""))));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, FormCollection> parseFormCollections(Object value) {
        Map<String, FormCollection> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> collections)) return result;
        for (Map.Entry<?, ?> entry : collections.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> collection = (Map<String, Object>) raw;
            String formId = String.valueOf(collection.getOrDefault("formId", ""));
            if (formId.isBlank()) continue;
            String compactJson = String.valueOf(collection.getOrDefault("compactJson", "{}"));
            List<BusinessDataChunker.DataChunk> chunks = parseDataChunks(collection.get("chunks"), compactJson);
            result.put(String.valueOf(entry.getKey()), new FormCollection(
                    String.valueOf(collection.getOrDefault("jobId", "")), formId,
                    String.valueOf(collection.getOrDefault("formName", formId)),
                    chunks, intValue(collection.get("recordCount")), compactJson,
                    !Boolean.FALSE.equals(collection.get("complete")), stringList(collection.get("dataGaps")),
                    stringList(collection.get("dependencyEventIds"))));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<BusinessDataChunker.DataChunk> parseDataChunks(Object value, String fallbackJson) {
        List<BusinessDataChunker.DataChunk> chunks = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> chunk = (Map<String, Object>) raw;
                String json = String.valueOf(chunk.getOrDefault("json", "{}"));
                chunks.add(new BusinessDataChunker.DataChunk(
                        intValue(chunk.get("index")), intValue(chunk.get("count")), json,
                        intValue(chunk.get("recordEntries")), intValue(chunk.getOrDefault("chars", json.length())),
                        stringList(chunk.get("recordFingerprints"))));
            }
        }
        if (chunks.isEmpty()) chunks.add(new BusinessDataChunker.DataChunk(
                1, 1, fallbackJson, 0, fallbackJson.length(), List.of()));
        return List.copyOf(chunks);
    }

    @SuppressWarnings("unchecked")
    private PendingExecution parsePendingExecution(Map<String, Object> pending) {
        Map<String, Object> input = objectMap(pending.get("input"));
        DeepSeekConversationClient.ToolCall call = new DeepSeekConversationClient.ToolCall(
                String.valueOf(pending.get("id")), String.valueOf(pending.get("name")), input);
        Instant startedAt = Instant.parse(String.valueOf(pending.get("startedAt")));
        List<McpQueryGateway.ToolObservation> observations = new ArrayList<>();
        if (pending.get("completedObservations") instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                Map<String, Object> observation = (Map<String, Object>) raw;
                observations.add(new McpQueryGateway.ToolObservation(
                        String.valueOf(observation.get("projectId")),
                        String.valueOf(observation.get("projectName")),
                        String.valueOf(observation.get("toolName")),
                        String.valueOf(observation.get("status")),
                        objectMap(observation.get("payload")),
                        observation.get("error") == null ? null : String.valueOf(observation.get("error")),
                        0, 0, Boolean.TRUE.equals(observation.get("truncated")),
                        nullableInt(observation.get("returnedCount")),
                        nullableInt(observation.get("reportedTotalCount")),
                        stringList(observation.get("traceEventIds"))));
            }
        }
        return new PendingExecution(call,
                pending.get("originalToolName") == null ? call.name() : String.valueOf(pending.get("originalToolName")),
                Boolean.TRUE.equals(pending.get("completed")), List.copyOf(observations),
                stringList(pending.get("failures")), intValue(pending.get("pollAttempts")), startedAt);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String formatConfidence(double value) {
        if (value < 0) return "0";
        return value == Math.rint(value) ? String.valueOf((int) value)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private Integer nullableInt(Object value) {
        return value == null ? null : intValue(value);
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int confidenceThreshold(int value, String label) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(label + "必须在 0 到 100 之间");
        return value;
    }

    private void checkCancelled(String requestId) {
        if (cancellationGuard != null) cancellationGuard.check(requestId);
    }

    private void savePhase(String requestId, QueryPhase phase, List<Map<String, Object>> messages,
                           int toolRounds, int logicalCalls, int physicalCalls, int pages, int chunks,
                           int inputTokens, int outputTokens, int cacheHits, int successfulObservations,
                           String stopReason) {
        if (checkpoints == null) return;
        try {
            Map<String, Object> state = checkpointState(phase, messages, toolRounds, logicalCalls,
                    physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits,
                    successfulObservations, stopReason);
            checkpoints.saveProgress(requestId, phase, objectMapper.writeValueAsString(state));
        } catch (Exception ignored) {
            // Checkpoint 写入失败不影响当前请求；Trace 会保留实际调用。
        }
    }

    private void saveOrchestrationState(String requestId, QueryPhase phase,
                                        List<Map<String, Object>> messages,
                                        int toolRounds, int logicalCalls, int physicalCalls, int pages, int chunks,
                                        int inputTokens, int outputTokens, int cacheHits,
                                        int successfulObservations, String stopReason,
                                        OrchestrationStage orchestrationStage,
                                        Map<String, FormCandidate> candidateForms, Set<String> selectedFormIds,
                                        List<FormCandidate> rejectedForms, int discoveredFormCount,
                                        Map<String, FormCollection> formCollections,
                                        List<Map<String, Object>> collectedSummaries, List<String> failures,
                                        Set<String> toolsUsed, Set<String> projectsQueried) {
        if (checkpoints == null) return;
        try {
            Map<String, Object> state = checkpointState(phase, messages, toolRounds, logicalCalls,
                    physicalCalls, pages, chunks, inputTokens, outputTokens, cacheHits,
                    successfulObservations, stopReason);
            putOrchestrationState(state, orchestrationStage, candidateForms, selectedFormIds,
                    rejectedForms, discoveredFormCount, formCollections, collectedSummaries,
                    failures, toolsUsed, projectsQueried);
            checkpoints.saveProgress(requestId, phase, objectMapper.writeValueAsString(state));
        } catch (Exception ignored) {
            // Checkpoint 写入失败不影响当前请求；Trace 会保留实际调用。
        }
    }

    private Map<String, Object> checkpointState(QueryPhase phase, List<Map<String, Object>> messages,
                                                int toolRounds, int logicalCalls, int physicalCalls,
                                                int pages, int chunks, int inputTokens, int outputTokens,
                                                int cacheHits, int successfulObservations, String stopReason) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("messages", messages);
        state.put("toolRounds", toolRounds);
        state.put("logicalToolCalls", logicalCalls);
        state.put("physicalMcpCalls", physicalCalls);
        state.put("pages", pages);
        state.put("chunks", chunks);
        state.put("inputTokens", inputTokens);
        state.put("outputTokens", outputTokens);
        state.put("cacheHits", cacheHits);
        state.put("successfulObservations", successfulObservations);
        state.put("executionStage", phase.name());
        if (stopReason != null) state.put("stopReason", stopReason);
        return state;
    }

    private void putOrchestrationState(Map<String, Object> state, OrchestrationStage orchestrationStage,
                                       Map<String, FormCandidate> candidateForms, Set<String> selectedFormIds,
                                       List<FormCandidate> rejectedForms, int discoveredFormCount,
                                       Map<String, FormCollection> formCollections,
                                       List<Map<String, Object>> collectedSummaries, List<String> failures,
                                       Set<String> toolsUsed, Set<String> projectsQueried) {
        state.put("orchestrationStage", orchestrationStage.name());
        state.put("candidateForms", candidateForms);
        state.put("selectedFormIds", selectedFormIds);
        state.put("rejectedForms", rejectedForms);
        state.put("discoveredFormCount", discoveredFormCount);
        state.put("formCollections", formCollections);
        state.put("collectedSummaries", collectedSummaries);
        state.put("failures", failures);
        state.put("toolsUsed", toolsUsed);
        state.put("projectsQueried", projectsQueried);
    }

    private boolean savePendingExecutions(String requestId, List<Map<String, Object>> messages,
                                  List<ExecutedCall> executions, int pollAttempts,
                                  Instant startedAt, Duration delay,
                                  int toolRounds, int logicalCalls, int physicalCalls, int pages, int chunks,
                                  int inputTokens, int outputTokens, int cacheHits, int successfulObservations,
                                  OrchestrationStage orchestrationStage,
                                  Map<String, FormCandidate> candidateForms, Set<String> selectedFormIds,
                                  List<FormCandidate> rejectedForms, int discoveredFormCount,
                                  Map<String, FormCollection> formCollections,
                                  List<Map<String, Object>> collectedSummaries, List<String> failures,
                                  Set<String> toolsUsed, Set<String> projectsQueried) {
        if (checkpoints == null) return false;
        try {
            List<Map<String, Object>> pendingExecutions = new ArrayList<>();
            for (ExecutedCall execution : executions) {
                boolean completed = !isWaitingAsync(execution);
                DeepSeekConversationClient.ToolCall savedCall = completed
                        ? execution.call() : continuationCall(execution);
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("id", savedCall.id());
                pending.put("name", savedCall.name());
                pending.put("input", savedCall.input());
                pending.put("originalToolName", execution.originalToolName());
                pending.put("completed", completed);
                pending.put("pollAttempts", pollAttempts);
                pending.put("startedAt", startedAt.toString());
                pending.put("failures", execution.failures());
                if (completed) {
                    pending.put("completedObservations", execution.observations().stream()
                            .map(this::checkpointObservation).toList());
                }
                pendingExecutions.add(pending);
            }
            Map<String, Object> state = checkpointState(QueryPhase.POLLING_ASYNC, messages,
                    toolRounds, logicalCalls, physicalCalls, pages, chunks, inputTokens, outputTokens,
                    cacheHits, successfulObservations, null);
            state.put("pendingExecutions", pendingExecutions);
            state.put("pollAttempts", pollAttempts);
            if (executions.stream().flatMap(execution -> execution.observations().stream()).anyMatch(observation ->
                    Boolean.TRUE.equals(observation.payload().get("retryable")))) {
                state.put("retryCount", pollAttempts);
            }
            state.put("asyncStatusTransitions", executions.stream()
                    .flatMap(execution -> execution.observations().stream())
                    .map(observation -> observation.payload().get("asyncTaskState"))
                    .filter(java.util.Objects::nonNull).map(String::valueOf).distinct().toList());
            putOrchestrationState(state, orchestrationStage, candidateForms, selectedFormIds,
                    rejectedForms, discoveredFormCount, formCollections, collectedSummaries,
                    failures, toolsUsed, projectsQueried);
            QuerySession session = checkpoints.load(requestId).orElse(null);
            QueryPhase phase = executions.stream().anyMatch(this::isPaginationWaiting)
                    ? QueryPhase.FETCHING_PAGE : QueryPhase.POLLING_ASYNC;
            state.put("executionStage", phase.name());
            return session != null && checkpoints.advance(requestId, session.version(), phase,
                    objectMapper.writeValueAsString(state), clock.instant().plus(delay));
        } catch (Exception ignored) {
            // 轮询仍可由恢复扫描补偿，检查点失败不覆盖 MCP 结果。
            return false;
        }
    }

    private Map<String, Object> checkpointObservation(McpQueryGateway.ToolObservation observation) {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("projectId", observation.projectId());
        saved.put("projectName", observation.projectName());
        saved.put("toolName", observation.toolName());
        saved.put("status", observation.status());
        saved.put("payload", observation.payload());
        saved.put("error", observation.error());
        saved.put("truncated", observation.truncated());
        saved.put("returnedCount", observation.returnedCount());
        saved.put("reportedTotalCount", observation.reportedTotalCount());
        saved.put("traceEventIds", observation.traceEventIds());
        return saved;
    }

    private DeepSeekConversationClient.ToolCall continuationCall(ExecutedCall execution) {
        Map<String, Object> paginationStates = new LinkedHashMap<>();
        for (McpQueryGateway.ToolObservation observation : execution.observations()) {
            Object state = observation.payload().get("paginationState");
            if (state != null) paginationStates.put(observation.projectId(), state);
        }
        if (paginationStates.isEmpty()) return execution.call();
        Map<String, Object> input = new LinkedHashMap<>(execution.call().input());
        Map<String, Object> arguments = new LinkedHashMap<>(objectMap(input.get("arguments")));
        arguments.put("_paginationStates", paginationStates);
        input.put("arguments", arguments);
        return new DeepSeekConversationClient.ToolCall(execution.call().id(), execution.call().name(), input);
    }

    private int physicalCalls(ExecutedCall execution) {
        return execution.observations().stream().mapToInt(McpQueryGateway.ToolObservation::physicalCalls).sum();
    }

    private int pages(ExecutedCall execution) {
        return execution.observations().stream().mapToInt(McpQueryGateway.ToolObservation::pages).sum();
    }

    private String readable(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "AI 服务未返回有效答案。" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> distinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(this::hasText).distinct().toList();
    }

    private QueryExecutionException queryFailure(Exception cause, String model, int toolRounds, int logicalCalls,
                                                  int physicalCalls, int pages, int chunks, int historyTurns,
                                                  int inputTokens, int outputTokens, Set<String> toolsUsed,
                                                  Set<String> projectsQueried, List<String> failures) {
        return new QueryExecutionException(readable(cause), cause, model, toolRounds, logicalCalls, physicalCalls,
                pages, chunks, historyTurns, inputTokens, outputTokens, List.copyOf(toolsUsed),
                List.copyOf(projectsQueried), distinct(failures));
    }

    private enum OrchestrationStage {
        CONTEXT,
        MATCH_DISCOVERY,
        LIST_DISCOVERY,
        COLLECTION_PLANNING,
        DONE
    }

    private enum RoutingDecisionType { DIRECT_ANSWER, USE_MCP, NEEDS_CLARIFICATION }

    private record ToolConfidence(String name, double confidence, String reason) {}

    private record RoutingDecision(RoutingDecisionType decision, double mcpConfidence, String answer,
                                   String clarification, List<ToolConfidence> tools) {
        Set<String> acceptedTools(int threshold) {
            return tools.stream().filter(tool -> tool.confidence() >= threshold)
                    .map(ToolConfidence::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private record ExecutedCall(DeepSeekConversationClient.ToolCall call, String originalToolName,
                                List<McpQueryGateway.ToolObservation> observations, List<String> failures,
                                String signature, boolean cacheHit) {
        ExecutedCall withCache(String value, boolean hit) {
            return new ExecutedCall(call, originalToolName, observations, failures, value, hit);
        }
    }
    private record CompactedObservation(String content, int chunks, int inputTokens, int outputTokens,
                                        List<String> traceEventIds) {}
    private record FormCollection(String jobId, String formId, String formName,
                                  List<BusinessDataChunker.DataChunk> chunks, int recordCount,
                                  String compactJson, boolean complete, List<String> dataGaps,
                                  List<String> dependencyEventIds) {}
    private record FormCandidate(String key, String id, String name, String description,
                                 List<String> projectIds, String source, double confidence, String reason) {
        FormCandidate ranked(double score, String explanation) {
            return new FormCandidate(key, id, name, description, projectIds, source, score, explanation);
        }
    }
    private record FormScore(double confidence, String reason) {}
    private record FormRankingBatch(Map<String, FormCandidate> accepted, List<FormCandidate> rejected,
                                    int discoveredCount, int inputTokens, int outputTokens,
                                    int modelCalls, List<String> traceEventIds) {
        static FormRankingBatch empty() {
            return new FormRankingBatch(Map.of(), List.of(), 0, 0, 0, 0, List.of());
        }
    }
    private record FormAnalysisResult(String jobId, String formId, String formName, String analysis,
                                      boolean fallback, int inputTokens, int outputTokens,
                                      int chunkCount, List<String> traceEventIds, String failure) {}
    private record FormAnalysisBatch(List<FormAnalysisResult> results, int inputTokens, int outputTokens,
                                     int chunkCount,
                                     List<String> traceEventIds, List<String> failures) {
        static FormAnalysisBatch empty() {
            return new FormAnalysisBatch(List.of(), 0, 0, 0, List.of(), List.of());
        }
    }
    private record TemporalContext(LocalDate today, TemporalRange range, String instruction) {}
    private record PendingExecution(DeepSeekConversationClient.ToolCall call, String originalToolName,
                                    boolean completed,
                                    List<McpQueryGateway.ToolObservation> completedObservations,
                                    List<String> failures, int pollAttempts, Instant startedAt) {}
    private record ResumedState(List<Map<String, Object>> messages, List<PendingExecution> pendingExecutions,
                                int toolRounds, int logicalCalls, int physicalCalls, int pages, int chunks,
                                int inputTokens, int outputTokens, int cacheHits, int successfulObservations,
                                OrchestrationStage orchestrationStage,
                                Map<String, FormCandidate> candidateForms, Set<String> selectedFormIds,
                                List<FormCandidate> rejectedForms, int discoveredFormCount,
                                Map<String, FormCollection> formCollections,
                                List<Map<String, Object>> collectedSummaries, List<String> failures,
                                Set<String> toolsUsed, Set<String> projectsQueried) {
        static ResumedState empty() {
            return new ResumedState(List.of(), List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    null, Map.of(), Set.of(), List.of(), 0, Map.of(), List.of(), List.of(), Set.of(), Set.of());
        }
    }

    public record QueryRequest(String requestId, String question, String chatType, String openId, String chatId,
                               java.time.Instant createdAt) {
        public QueryRequest(String requestId, String question, String chatType, String openId, String chatId) {
            this(requestId, question, chatType, openId, chatId, null);
        }
    }
    public record QueryResult(String path, String answer, AnswerPresentation presentation, String presentationJson,
                              String model, int toolRounds, int logicalToolCalls,
                              int physicalMcpCalls, int pages, int chunks, int historyTurns,
                              List<String> toolsUsed, List<String> projectsQueried, List<String> failures,
                              String stopReason, int inputTokens, int outputTokens, int cacheHits,
                              int discoveredFormCount, int acceptedFormCount, int rejectedFormCount,
                              boolean managementConclusionLimited, long latencyMs) {
        public QueryResult(String path, String answer, AnswerPresentation presentation, String presentationJson,
                           String model, int toolRounds, int logicalToolCalls,
                           int physicalMcpCalls, int pages, int chunks, int historyTurns,
                           List<String> toolsUsed, List<String> projectsQueried, List<String> failures,
                           String stopReason, int inputTokens, int outputTokens, int cacheHits, long latencyMs) {
            this(path, answer, presentation, presentationJson, model, toolRounds, logicalToolCalls,
                    physicalMcpCalls, pages, chunks, historyTurns, toolsUsed, projectsQueried, failures,
                    stopReason, inputTokens, outputTokens, cacheHits, 0, 0, 0, false, latencyMs);
        }
        public QueryResult(String path, String answer, AnswerPresentation presentation, String presentationJson,
                           String model, int toolRounds, int logicalToolCalls,
                           int physicalMcpCalls, int pages, int chunks, int historyTurns,
                           List<String> toolsUsed, List<String> projectsQueried, List<String> failures,
                           String stopReason, int inputTokens, int outputTokens, long latencyMs) {
            this(path, answer, presentation, presentationJson, model, toolRounds, logicalToolCalls,
                    physicalMcpCalls, pages, chunks, historyTurns, toolsUsed, projectsQueried, failures,
                    stopReason, inputTokens, outputTokens, 0, 0, 0, 0, false, latencyMs);
        }
        public QueryResult(String path, String answer, String model, int toolRounds, int logicalToolCalls,
                           int physicalMcpCalls, int pages, int chunks, int historyTurns,
                           List<String> toolsUsed, List<String> projectsQueried, List<String> failures,
                           String stopReason, int inputTokens, int outputTokens, long latencyMs) {
            this(path, answer, AnswerPresentation.markdownOnly(answer), null, model, toolRounds,
                    logicalToolCalls, physicalMcpCalls, pages, chunks, historyTurns, toolsUsed,
                    projectsQueried, failures, stopReason, inputTokens, outputTokens, 0,
                    0, 0, 0, false, latencyMs);
        }
    }

    public static final class QueryExecutionException extends RuntimeException {
        private final String model;
        private final int toolRounds;
        private final int logicalToolCalls;
        private final int physicalMcpCalls;
        private final int pages;
        private final int chunks;
        private final int historyTurns;
        private final int inputTokens;
        private final int outputTokens;
        private final List<String> toolsUsed;
        private final List<String> projectsQueried;
        private final List<String> failures;

        QueryExecutionException(String message, Throwable cause, String model, int toolRounds, int logicalToolCalls,
                                int physicalMcpCalls, int pages, int chunks, int historyTurns, int inputTokens,
                                int outputTokens, List<String> toolsUsed, List<String> projectsQueried,
                                List<String> failures) {
            super(message, cause);
            this.model = model;
            this.toolRounds = toolRounds;
            this.logicalToolCalls = logicalToolCalls;
            this.physicalMcpCalls = physicalMcpCalls;
            this.pages = pages;
            this.chunks = chunks;
            this.historyTurns = historyTurns;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.toolsUsed = toolsUsed;
            this.projectsQueried = projectsQueried;
            this.failures = failures;
        }

        public String model() { return model; }
        public int toolRounds() { return toolRounds; }
        public int logicalToolCalls() { return logicalToolCalls; }
        public int physicalMcpCalls() { return physicalMcpCalls; }
        public int pages() { return pages; }
        public int chunks() { return chunks; }
        public int historyTurns() { return historyTurns; }
        public int inputTokens() { return inputTokens; }
        public int outputTokens() { return outputTokens; }
        public List<String> toolsUsed() { return toolsUsed; }
        public List<String> projectsQueried() { return projectsQueried; }
        public List<String> failures() { return failures; }
    }

    public static final class QueryPendingException extends RuntimeException {
        private final Duration resumeAfter;

        public QueryPendingException(Duration resumeAfter) {
            super("异步 MCP 任务仍在处理，已保存检查点");
            this.resumeAfter = resumeAfter;
        }

        public Duration resumeAfter() { return resumeAfter; }
    }
}
