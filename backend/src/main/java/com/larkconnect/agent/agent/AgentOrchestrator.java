package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AnswerEngineRouter;
import com.larkconnect.agent.ai.FastGptClient;
import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.audit.ChainTrace;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.deepseek.DeepSeekClient;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.mcp.McpAdapter.PageData;
import com.larkconnect.agent.mcp.McpAdapter.PaginationResult;
import com.larkconnect.agent.mcp.McpToolRegistry;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AgentOrchestrator {
    private final AgentTaskService taskService;
    private final DeepSeekClient deepSeekClient;
    private final TokenResolver tokenResolver;
    private final McpToolRegistry toolRegistry;
    private final McpAdapter mcpAdapter;
    private final FeishuClient feishuClient;
    private final AuditService auditService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentServiceClient agentServiceClient;
    private final IntentRouter intentRouter;
    private final AnswerEngineRouter answerEngineRouter;
    private final ChainTraceService chainTraceService;
    private static final int MAX_AGENT_SERVICE_ROUNDS = 4;

    public AgentOrchestrator(
            AgentTaskService taskService,
            DeepSeekClient deepSeekClient,
            TokenResolver tokenResolver,
            McpToolRegistry toolRegistry,
            McpAdapter mcpAdapter,
            FeishuClient feishuClient,
            AuditService auditService,
            AppProperties properties,
            ObjectMapper objectMapper,
            AgentServiceClient agentServiceClient,
            IntentRouter intentRouter,
            AnswerEngineRouter answerEngineRouter,
            ChainTraceService chainTraceService
    ) {
        this.taskService = taskService;
        this.deepSeekClient = deepSeekClient;
        this.tokenResolver = tokenResolver;
        this.toolRegistry = toolRegistry;
        this.mcpAdapter = mcpAdapter;
        this.feishuClient = feishuClient;
        this.auditService = auditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.agentServiceClient = agentServiceClient;
        this.intentRouter = intentRouter;
        this.answerEngineRouter = answerEngineRouter;
        this.chainTraceService = chainTraceService;
    }

    public void process(String requestId) {
        long started = System.currentTimeMillis();
        Map<String, Object> task = taskService.loadTask(requestId);
        String question = task.get("message_text").toString();
        String messageId = task.get("feishu_message_id").toString();
        String chatId = task.get("feishu_chat_id").toString();
        String openId = task.get("feishu_open_id").toString();
        String chatType = task.get("chat_type").toString();

        IntentRoute route = intentRouter.route(question);
        if (route.category() == IntentCategory.MCP_CONFIG_STATUS) {
            processMcpConfigStatus(requestId, question, messageId, chatId, openId, chatType, route, started);
            return;
        }
        if (answerEngineRouter.shouldUseFastGpt(route)) {
            try {
                processWithFastGpt(requestId, question, messageId, chatId, openId, chatType, route, started);
                return;
            } catch (Exception e) {
                auditService.writeModel(requestId, "fastgpt", "fastgpt_fallback", question, "", Status.FAILED, System.currentTimeMillis() - started, readableError(e));
            }
        }
        if (!route.requiresMcp()) {
            processNonMcpRoute(requestId, question, messageId, chatId, openId, route, started);
            return;
        }

        if (agentServiceClient.isEnabled()) {
            try {
                processWithAgentService(requestId, question, messageId, chatId, openId, chatType, route, started);
                return;
            } catch (Exception e) {
                auditService.writeModel(requestId, "agent-service", "agent_service_fallback", question, "", Status.FAILED, 0, e.getMessage());
            }
        }
        processWithDeepSeekPlan(requestId, question, messageId, chatId, openId, chatType, route, started);
    }

    private void processWithFastGpt(String requestId, String question, String messageId, String chatId, String openId, String chatType, IntentRoute route, long started) {
        FastGptClient.FastGptResponse response = answerEngineRouter.answerWithFastGpt(new FastGptClient.FastGptRequest(
                requestId,
                question,
                openId,
                fastGptChatId(openId, chatId, chatType),
                chatType
        ));
        auditService.writeModel(requestId, "fastgpt", "fastgpt_answer", question, response.rawResponse(), Status.SUCCEEDED, response.latencyMs(), null);
        feishuClient.replyAnswerCard(messageId, question, response.answer(), route.title(), route.cardTemplate());
        auditService.writeMain(requestId, openId, chatId, null, List.of(), question, "fastgpt_answer", response.answer(), System.currentTimeMillis() - started, null);
    }

    private void processNonMcpRoute(String requestId, String question, String messageId, String chatId, String openId, IntentRoute route, long started) {
        String answer = switch (route.category()) {
            case SYSTEM_CONFIG -> configurationHint("这是系统配置类问题，不需要调用 Primelayer MCP。");
            case WEATHER_EXTERNAL -> deepSeekClient.answerGeneral(question + "\n\n请注意：当前系统尚未接入实时天气 API 或天气 MCP 工具。如果需要实时天气，请说明需要接入外部天气数据源。");
            case GENERAL_CHAT -> deepSeekClient.answerGeneral(question);
            default -> deepSeekClient.answerGeneral(question);
        };
        feishuClient.replyAnswerCard(messageId, question, answer, route.title(), route.cardTemplate());
        auditService.writeModel(requestId, properties.deepseek().model(), route.skillId(), question, answer, Status.SUCCEEDED, 0, null);
        auditService.writeMain(requestId, openId, chatId, null, List.of(), question, route.skillId(), answer, System.currentTimeMillis() - started, null);
    }

    private void processMcpConfigStatus(String requestId, String question, String messageId, String chatId, String openId, String chatType, IntentRoute route, long started) {
        TokenResolver.McpConfigCheckResult checkResult = tokenResolver.checkMcpConfig(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
        if (!checkResult.configured()) {
            feishuClient.replyMcpRequiredCard(messageId, checkResult);
            auditService.writeMain(requestId, openId, chatId, null, List.of(), question, route.skillId(), checkResult.reason(), System.currentTimeMillis() - started, checkResult.reason());
            return;
        }

        TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
        if (context.hasError()) {
            String answer = configurationHint(context.errorMessage());
            feishuClient.replyAnswerCard(messageId, question, answer, "MCP 配置状态", "orange");
            auditService.writeMain(requestId, openId, chatId, checkResult.primelayerUserId(), List.of(), question, route.skillId(), answer, System.currentTimeMillis() - started, context.errorMessage());
            return;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (TokenResolver.TokenEntry token : context.tokens()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", token.projectId());
            item.put("projectName", token.projectName());
            try {
                List<Map<String, Object>> tools = extractTools(mcpAdapter.listTools(token.token()));
                tokenResolver.updateVerificationStatus(token.tokenId(), "VERIFIED", null);
                item.put("ok", true);
                item.put("toolCount", tools.size());
                passed++;
            } catch (Exception e) {
                String error = readableError(e);
                tokenResolver.updateVerificationStatus(token.tokenId(), "FAILED", error);
                item.put("ok", false);
                item.put("error", error);
            }
            results.add(item);
        }

        String answer = buildMcpConfigStatusAnswer(checkResult, results, passed);
        String template = passed == results.size() ? "green" : passed > 0 ? "orange" : "red";
        feishuClient.replyAnswerCard(messageId, question, answer, route.title(), template);
        auditService.writeMain(
                requestId,
                openId,
                chatId,
                context.primelayerUserId(),
                context.tokens().stream().map(TokenResolver.TokenEntry::projectId).toList(),
                question,
                route.skillId(),
                answer,
                System.currentTimeMillis() - started,
                passed == results.size() ? null : "MCP 配置实时验证存在失败项目"
        );
    }

    private void processWithAgentService(String requestId, String question, String messageId, String chatId, String openId, String chatType, IntentRoute route, long started) {
        TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
        if (context.hasError()) {
            TokenResolver.McpConfigCheckResult checkResult = tokenResolver.checkMcpConfig(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
            String error = checkResult.configured() ? context.errorMessage() : checkResult.reason();
            String answer = projectDataUnavailableMessage();
            feishuClient.replyAnswerCard(messageId, question, answer, "项目数据暂不可用", "orange");
            auditService.writeMain(requestId, openId, chatId, checkResult.primelayerUserId(), List.of(), question, "agent_service_context", answer, System.currentTimeMillis() - started, error);
            return;
        }

        List<Map<String, Object>> availableTools = loadAvailableTools(context.tokens());
        List<Map<String, Object>> toolResults = new ArrayList<>();
        AgentServiceDtos.AgentAnswerResponse plan = null;
        String answer = null;

        for (int round = 1; round <= MAX_AGENT_SERVICE_ROUNDS; round++) {
            AgentServiceDtos.AgentAnswerRequest planRequest = agentRequest(requestId, question, chatType, openId, context, availableTools, toolResults);
            plan = agentServiceClient.answer(planRequest);
            auditService.writeModel(requestId, "agent-service", round == 1 ? "plan" : "round_" + round, question, toJson(plan), Status.SUCCEEDED, 0, null);

            if (plan.requiresClarification()) {
                String clarification = hasText(plan.clarificationQuestion()) ? plan.clarificationQuestion() : "请补充项目名称或查询范围。";
                feishuClient.replyAnswerCard(messageId, question, clarification, "需要补充信息", "orange");
                auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(), List.of(), question, plan.skillId(), clarification, System.currentTimeMillis() - started, null);
                return;
            }

            if (hasText(plan.answer())) {
                answer = plan.answer();
                break;
            }

            List<AgentServiceDtos.ToolCall> requestedCalls = plan.toolCalls() == null ? List.of() : plan.toolCalls();
            if (requestedCalls.isEmpty()) {
                break;
            }

            List<Map<String, Object>> roundResults = executeAgentToolCalls(requestId, context, plan, availableTools);
            toolResults.addAll(roundResults);
            if (roundResults.isEmpty()) {
                break;
            }
        }

        if (!hasText(answer) && !toolResults.isEmpty()) {
            answer = deepSeekClient.summarize(question, toolResults);
        }
        if (!hasText(answer)) {
            answer = "暂时没有从项目数据中得到可汇总结果。你可以换一种问法，或稍后重试。";
        }

        feishuClient.replyAnswerCard(messageId, question, answer, route.title(), route.cardTemplate());
        auditService.writeMain(
                requestId,
                openId,
                chatId,
                context.primelayerUserId(),
                plan == null ? context.tokens().stream().map(TokenResolver.TokenEntry::projectId).toList() : selectedProjectIds(plan, context),
                question,
                plan == null ? route.skillId() : plan.skillId(),
                answer,
                System.currentTimeMillis() - started,
                null
        );
    }

    private void processWithDeepSeekPlan(String requestId, String question, String messageId, String chatId, String openId, String chatType, IntentRoute route, long started) {
        TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
        if (context.hasError()) {
            TokenResolver.McpConfigCheckResult checkResult = tokenResolver.checkMcpConfig(openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
            String error = checkResult.configured() ? context.errorMessage() : checkResult.reason();
            String answer = configurationHint(error);
            feishuClient.replyAnswerCard(messageId, question, answer, "MCP 配置异常", "orange");
            auditService.writeMain(requestId, openId, chatId, checkResult.primelayerUserId(), List.of(), question, "deepseek_multi_tool", answer, System.currentTimeMillis() - started, error);
            return;
        }

        List<Map<String, Object>> availableTools = loadAvailableTools(context.tokens());
        if (availableTools.isEmpty()) {
            feishuClient.replyAnswerCard(messageId, question, "当前没有可用的 MCP 工具。", "工具不可用", "orange");
            auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(), List.of(), question, "deepseek_multi_tool", "无可用 MCP 工具", System.currentTimeMillis() - started, null);
            return;
        }

        ChainTrace trace = new ChainTrace(requestId);

        DeepSeekPlan plan = deepSeekClient.planMultiTool(question, availableTools);
        trace.addPlanNode(question + "\n\n可用工具: " + availableTools.size() + " 个", toJson(plan), System.currentTimeMillis() - started);
        auditService.writeModel(requestId, properties.deepseek().model(), "plan_multi_tool", question, toJson(plan), Status.SUCCEEDED, 0, null);

        if (plan.needClarification()) {
            feishuClient.replyAnswerCard(messageId, question, plan.clarificationQuestion(), "需要补充信息", "orange");
            auditService.writeMain(requestId, openId, chatId, null, List.of(), question, plan.intent(), plan.clarificationQuestion(), System.currentTimeMillis() - started, null);
            return;
        }

        Map<String, List<Map<String, Object>>> allToolResults = new LinkedHashMap<>();
        int totalPages = 0;
        int totalMcpCalls = 0;
        List<String> toolNames = new ArrayList<>();
        List<String> projectNames = new ArrayList<>();
        String prevNodeId = "plan";

        for (DeepSeekPlan.ToolCall toolCall : plan.toolCalls()) {
            String toolName = toolCall.toolName();
            toolNames.add(toolName);
            List<Map<String, Object>> toolAllResults = new ArrayList<>();

            for (TokenResolver.TokenEntry token : context.tokens()) {
                if (!projectNames.contains(token.projectId())) {
                    projectNames.add(token.projectId());
                }
                Map<String, Object> args = new LinkedHashMap<>(toolCall.arguments());
                args.put("project_id", token.projectId());
                args.put("primelayer_user_id", context.primelayerUserId());

                PaginationResult result = mcpAdapter.callToolWithPagination(token.token(), toolName, args);
                totalPages += result.totalPages();
                totalMcpCalls += result.pages().size();

                for (int i = 0; i < result.pages().size(); i++) {
                    PageData page = result.pages().get(i);
                    trace.addMcpCallNode(toolName, token.projectId(), token.projectName(), page, i);
                    String nodeId = trace.lastNodeId();
                    trace.addEdge(new ChainTrace.Edge(prevNodeId, nodeId));
                    prevNodeId = nodeId;

                    auditService.writeTool(requestId, token.projectId(), context.primelayerUserId(), toolName, args, page.status(), page.latencyMs(), page.error());
                }

                for (PageData page : result.pages()) {
                    if (Status.SUCCEEDED.equals(page.status()) && page.rawResponse() != null) {
                        toolAllResults.add(page.rawResponse());
                    }
                }
            }
            allToolResults.put(toolName, toolAllResults);
        }

        List<DeepSeekClient.PerToolAnalysis> perToolAnalyses = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : allToolResults.entrySet()) {
            String toolName = entry.getKey();
            List<Map<String, Object>> results = entry.getValue();
            if (results.isEmpty()) continue;

            long analyzeStarted = System.currentTimeMillis();
            String analysis = deepSeekClient.analyzePerTool(toolName, results);
            long analyzeLatency = System.currentTimeMillis() - analyzeStarted;

            String nodeId = "analyze_per_" + toolName.replaceAll("[^a-zA-Z0-9_]", "_");
            trace.addAnalyzeNode(nodeId, "阶段1: 分析 " + toolName, "全量数据（" + results.size() + " 页）", analysis, analyzeLatency);
            trace.addEdge(new ChainTrace.Edge(prevNodeId, nodeId));
            prevNodeId = nodeId;

            perToolAnalyses.add(new DeepSeekClient.PerToolAnalysis(toolName, analysis));
            auditService.writeModel(requestId, properties.deepseek().model(), "analyze_per_tool_" + toolName, "tool=" + toolName + " pages=" + results.size(), analysis, Status.SUCCEEDED, analyzeLatency, null);
        }

        long crossStarted = System.currentTimeMillis();
        String finalAnswer = deepSeekClient.analyzeCross(question, perToolAnalyses);
        long crossLatency = System.currentTimeMillis() - crossStarted;

        trace.addAnalyzeNode("analyze_cross", "阶段2: 交叉分析", question + "\n\n" + perToolAnalyses.size() + " 个工具分析结果", finalAnswer, crossLatency);
        trace.addEdge(new ChainTrace.Edge(prevNodeId, "analyze_cross"));
        auditService.writeModel(requestId, properties.deepseek().model(), "analyze_cross", question, finalAnswer, Status.SUCCEEDED, crossLatency, null);

        trace.summary.totalMcpCalls = totalMcpCalls;
        trace.summary.totalPages = totalPages;
        trace.summary.totalLatencyMs = System.currentTimeMillis() - started;
        trace.summary.toolsUsed = toolNames;
        trace.summary.projectsQueried = projectNames;

        chainTraceService.save(requestId, trace);

        feishuClient.replyAnswerCard(messageId, question, finalAnswer, route.title(), route.cardTemplate());
        auditService.writeMain(requestId, openId, chatId, context.primelayerUserId(), projectNames, question, plan.intent(), finalAnswer, System.currentTimeMillis() - started, null);
    }

    private AgentServiceDtos.AgentAnswerRequest agentRequest(
            String requestId,
            String question,
            String chatType,
            String openId,
            TokenResolver.ResolvedContext context,
            List<Map<String, Object>> availableTools,
            List<Map<String, Object>> toolResults
    ) {
        return new AgentServiceDtos.AgentAnswerRequest(
                requestId,
                question,
                chatType,
                new AgentServiceDtos.UserContext(openId, context.primelayerUserId()),
                context.tokens().stream()
                        .map(token -> new AgentServiceDtos.ProjectRef(token.projectId(), token.projectName()))
                        .toList(),
                availableTools,
                List.of(),
                toolResults
        );
    }

    private List<Map<String, Object>> loadAvailableTools(List<TokenResolver.TokenEntry> tokens) {
        if (tokens.isEmpty()) {
            return toolRegistry.defaultToolMaps();
        }
        try {
            Map<String, Object> response = mcpAdapter.listTools(tokens.get(0).token());
            return toolRegistry.filterDiscoveredTools(extractTools(response));
        } catch (Exception e) {
            return toolRegistry.defaultToolMaps();
        }
    }

    private List<Map<String, Object>> executeAgentToolCalls(
            String requestId,
            TokenResolver.ResolvedContext context,
            AgentServiceDtos.AgentAnswerResponse plan,
            List<Map<String, Object>> availableTools
    ) {
        List<Map<String, Object>> toolResults = new ArrayList<>();
        List<AgentServiceDtos.ToolCall> toolCalls = plan.toolCalls() == null ? List.of() : plan.toolCalls();
        for (AgentServiceDtos.ToolCall toolCall : toolCalls) {
            for (TokenResolver.TokenEntry token : targetTokens(context.tokens(), toolCall.projectIds())) {
                toolResults.add(callAgentToolSafely(requestId, context.primelayerUserId(), token, toolCall, availableTools));
            }
        }
        return toolResults;
    }

    private Map<String, Object> callAgentToolSafely(
            String requestId,
            String primelayerUserId,
            TokenResolver.TokenEntry token,
            AgentServiceDtos.ToolCall toolCall,
            List<Map<String, Object>> availableTools
    ) {
        long toolStarted = System.currentTimeMillis();
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("projectId", token.projectId());
        toolResult.put("projectName", token.projectName());
        toolResult.put("toolName", toolCall.toolName());
        Map<String, Object> arguments = new LinkedHashMap<>(toolCall.arguments() == null ? Map.of() : toolCall.arguments());
        try {
            arguments.put("project_id", token.projectId());
            arguments.put("primelayer_user_id", primelayerUserId);
            toolRegistry.validate(toolCall.toolName(), arguments, availableTools);
            Map<String, Object> result = mcpAdapter.callTool(token.token(), toolCall.toolName(), arguments);
            toolResult.put("status", Status.SUCCEEDED);
            toolResult.put("arguments", arguments);
            toolResult.put("result", result);
            auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolCall.toolName(), arguments, Status.SUCCEEDED, System.currentTimeMillis() - toolStarted, null);
        } catch (Exception e) {
            toolResult.put("status", Status.FAILED);
            toolResult.put("arguments", arguments);
            toolResult.put("error", e.getMessage());
            auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolCall.toolName(), arguments, Status.FAILED, System.currentTimeMillis() - toolStarted, e.getMessage());
        }
        toolResult.put("latencyMs", System.currentTimeMillis() - toolStarted);
        return toolResult;
    }

    private List<TokenResolver.TokenEntry> targetTokens(List<TokenResolver.TokenEntry> tokens, List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return tokens;
        }
        Set<String> ids = projectIds.stream().collect(Collectors.toSet());
        return tokens.stream()
                .filter(token -> ids.contains(token.projectId()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> resultMap) {
            Object tools = resultMap.get("tools");
            if (tools instanceof List<?> toolList) {
                return toolList.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }
        }
        Object tools = response.get("tools");
        if (tools instanceof List<?> toolList) {
            return toolList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private List<String> selectedProjectIds(AgentServiceDtos.AgentAnswerResponse plan, TokenResolver.ResolvedContext context) {
        if (plan.projectIds() != null && !plan.projectIds().isEmpty()) {
            return plan.projectIds();
        }
        return context.tokens().stream().map(TokenResolver.TokenEntry::projectId).toList();
    }

    private String fastGptChatId(String openId, String chatId, String chatType) {
        if ("group".equalsIgnoreCase(chatType) && hasText(chatId)) {
            return chatId;
        }
        if (hasText(openId)) {
            return openId;
        }
        return hasText(chatId) ? chatId : "unknown";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String configurationHint(String errorMessage) {
        return errorMessage + "\n\n你看到这个提示，通常是因为当前飞书 open_id 还没有绑定 Primelayer 用户，或该用户没有可用的项目 MCP Token。\n\n处理方式：\n1. 打开后台「人员配置」。\n2. 在「人员绑定」中确认当前飞书 open_id 已绑定 Primelayer 用户 ID。\n3. 在「项目 MCP Token」中为该 Primelayer 用户新增或替换项目 Token。\n4. 如果是在群聊里提问，还需要在「群项目绑定」里绑定当前群和项目。";
    }

    private String projectDataUnavailableMessage() {
        return "暂时无法访问项目数据。我已经记录本次查询的诊断信息，你可以稍后重试；如果持续出现，请让管理员查看后台审计日志确认项目访问权限。";
    }

    private String buildMcpConfigStatusAnswer(TokenResolver.McpConfigCheckResult checkResult, List<Map<String, Object>> results, int passed) {
        StringBuilder builder = new StringBuilder();
        if (passed == results.size()) {
            builder.append("MCP 配置已绑定，且实时验证通过。");
        } else if (passed > 0) {
            builder.append("MCP 配置已绑定，但部分项目实时验证失败。");
        } else {
            builder.append("已找到 MCP Token 配置，但实时验证失败。");
        }
        builder.append("\n\n绑定对象：")
                .append(checkResult.ownerType())
                .append(" / ")
                .append(checkResult.ownerId())
                .append("\n验证结果：")
                .append(passed)
                .append("/")
                .append(results.size())
                .append(" 个项目通过");
        for (Map<String, Object> result : results) {
            builder.append("\n- ")
                    .append(result.getOrDefault("projectName", "-"))
                    .append(" (`")
                    .append(result.getOrDefault("projectId", "-"))
                    .append("`)：");
            if (Boolean.TRUE.equals(result.get("ok"))) {
                builder.append("通过，工具数量 ")
                        .append(result.getOrDefault("toolCount", 0));
            } else {
                builder.append("失败，")
                        .append(result.getOrDefault("error", "MCP 服务无响应或认证失败"));
            }
        }
        return builder.toString();
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "MCP 服务无响应或认证失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
