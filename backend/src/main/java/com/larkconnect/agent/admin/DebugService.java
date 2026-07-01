package com.larkconnect.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.ai.FastGptClient;
import com.larkconnect.agent.agent.AgentServiceClient;
import com.larkconnect.agent.agent.AgentServiceDtos;
import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekClient;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.feishu.FeishuEventParser;
import com.larkconnect.agent.feishu.FeishuIncomingMessage;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.mcp.McpToolRegistry;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DebugService {
    private final JdbcTemplate jdbcTemplate;
    private final ConnectionFactory rabbitConnectionFactory;
    private final AppProperties properties;
    private final DeepSeekClient deepSeekClient;
    private final TokenResolver tokenResolver;
    private final McpToolRegistry toolRegistry;
    private final McpAdapter mcpAdapter;
    private final TokenCryptoService cryptoService;
    private final FeishuClient feishuClient;
    private final FeishuEventParser feishuEventParser;
    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;
    private final AgentServiceClient agentServiceClient;
    private final AiRuntimeConfigService aiRuntimeConfigService;
    private final FastGptClient fastGptClient;

    public DebugService(
            JdbcTemplate jdbcTemplate,
            ConnectionFactory rabbitConnectionFactory,
            AppProperties properties,
            DeepSeekClient deepSeekClient,
            TokenResolver tokenResolver,
            McpToolRegistry toolRegistry,
            McpAdapter mcpAdapter,
            TokenCryptoService cryptoService,
            FeishuClient feishuClient,
            FeishuEventParser feishuEventParser,
            AgentTaskService agentTaskService,
            ObjectMapper objectMapper,
            AgentServiceClient agentServiceClient,
            AiRuntimeConfigService aiRuntimeConfigService,
            FastGptClient fastGptClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitConnectionFactory = rabbitConnectionFactory;
        this.properties = properties;
        this.deepSeekClient = deepSeekClient;
        this.tokenResolver = tokenResolver;
        this.toolRegistry = toolRegistry;
        this.mcpAdapter = mcpAdapter;
        this.cryptoService = cryptoService;
        this.feishuClient = feishuClient;
        this.feishuEventParser = feishuEventParser;
        this.agentTaskService = agentTaskService;
        this.objectMapper = objectMapper;
        this.agentServiceClient = agentServiceClient;
        this.aiRuntimeConfigService = aiRuntimeConfigService;
        this.fastGptClient = fastGptClient;
    }

    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("mysql", checkMysql());
        health.put("rabbitmq", checkRabbit());
        health.put("deepSeekApiKeyConfigured", hasText(properties.deepseek().apiKey()));
        health.put("deepSeekBaseUrl", properties.deepseek().baseUrl());
        health.put("deepSeekModel", properties.deepseek().model());
        health.put("mcpEndpointConfigured", hasText(properties.mcp().endpoint()));
        health.put("mcpEndpoint", properties.mcp().endpoint());
        health.put("mcpAuthHeaderName", properties.mcp().authHeaderName());
        health.put("agentServiceEnabled", properties.agentService().enabled());
        health.put("agentServiceBaseUrl", properties.agentService().baseUrl());
        AdminDtos.AiSettingsResponse aiSettings = aiRuntimeConfigService.publicSettings();
        health.put("aiEngine", aiSettings.engine());
        health.put("fastGptBaseUrl", aiSettings.fastGptBaseUrl());
        health.put("fastGptModel", aiSettings.fastGptModel());
        health.put("fastGptApiKeyConfigured", aiSettings.fastGptApiKeyConfigured());
        health.put("fastGptMemoryEnabled", aiSettings.fastGptMemoryEnabled());
        health.put("feishuAppIdConfigured", hasText(properties.feishu().appId()));
        health.put("feishuAppSecretConfigured", hasText(properties.feishu().appSecret()));
        health.put("feishuVerificationTokenConfigured", hasText(properties.feishu().verificationToken()));
        health.put("feishuEchoEnabled", properties.feishu().echoEnabled());
        return health;
    }

    public DeepSeekPlan plan(DebugDtos.DeepSeekPlanRequest request) {
        return deepSeekClient.plan(UUID.randomUUID().toString(), request.question(), defaultChatType(request.chatType()));
    }

    public Map<String, Object> testDeepSeekConnection(DebugDtos.DeepSeekConnectionRequest request) {
        return deepSeekClient.testConnection(request == null ? null : request.prompt());
    }

    public Map<String, Object> testFastGptConnection(DebugDtos.FastGptConnectionRequest request) {
        AiRuntimeConfigService.AiSettings settings = aiRuntimeConfigService.loadSettings();
        String prompt = request == null || !hasText(request.prompt()) ? "请只回复 OK，用于测试 FastGPT API 连通性。" : request.prompt();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", settings.fastGptBaseUrl());
        result.put("model", settings.fastGptModel());
        result.put("apiKeyConfigured", settings.fastGptApiKeyConfigured());
        try {
            FastGptClient.FastGptResponse response = fastGptClient.answer(
                    new FastGptClient.FastGptRequest(
                            UUID.randomUUID().toString(),
                            prompt,
                            "debug-open-id",
                            "debug-fastgpt",
                            "p2p"
                    ),
                    settings
            );
            result.put("ok", true);
            result.put("answer", response.answer());
            result.put("latencyMs", response.latencyMs());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", sanitizeError(e.getMessage(), settings.fastGptApiKey()));
        }
        return result;
    }

    public Map<String, Object> summarize(DebugDtos.DeepSeekSummarizeRequest request) {
        List<Map<String, Object>> toolResults = request.toolResults() == null ? List.of() : request.toolResults();
        String answer = deepSeekClient.summarize(request.question(), toolResults);
        return Map.of("answer", answer);
    }

    public Map<String, Object> callMcp(DebugDtos.McpCallRequest request) {
        TokenRecord token = loadToken(request.tokenId());
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : new LinkedHashMap<>(request.arguments());
        try {
            List<Map<String, Object>> tools = extractTools(mcpAdapter.listTools(token.token()));
            validateDiscoveredTool(request.toolName(), tools);
            Map<String, Object> result = mcpAdapter.callTool(token.token(), request.toolName(), arguments);
            return Map.of(
                    "ok", true,
                    "token", sanitizeToken(token),
                    "toolName", request.toolName(),
                    "arguments", arguments,
                    "result", result
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "token", sanitizeToken(token),
                    "toolName", request.toolName(),
                    "arguments", arguments,
                    "error", sanitizeError(e.getMessage(), token.token())
            );
        }
    }

    public Map<String, Object> listMcpTools(DebugDtos.McpToolsRequest request) {
        TokenRecord token = loadToken(request.tokenId());
        try {
            Map<String, Object> response = mcpAdapter.listTools(token.token());
            List<Map<String, Object>> tools = extractTools(response);
            return Map.of(
                    "ok", true,
                    "token", sanitizeToken(token),
                    "toolCount", tools.size(),
                    "tools", sanitizeTools(tools),
                    "raw", response
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "token", sanitizeToken(token),
                    "error", sanitizeError(e.getMessage(), token.token())
            );
        }
    }

    public Map<String, Object> askMcpByQuestion(DebugDtos.McpQuestionRequest request) {
        long started = System.currentTimeMillis();
        TokenRecord token = loadToken(request.tokenId());
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            Map<String, Object> toolsResponse = mcpAdapter.listTools(token.token());
            List<Map<String, Object>> tools = extractTools(toolsResponse);
            if (tools.isEmpty()) {
                throw new IllegalStateException("MCP 未返回可用工具");
            }
            Map<String, Object> plan = deterministicMcpPlan(request.question(), tools);
            if (plan.isEmpty()) {
                plan = deepSeekClient.planMcpDebugCall(request.question(), compactTools(tools));
                plan.putIfAbsent("planner", "deepseek");
            }
            String toolName = String.valueOf(plan.getOrDefault("toolName", ""));
            validateDiscoveredTool(toolName, tools);
            Map<String, Object> arguments = normalizeQuestionArguments(request.question(), toolName, plan.get("arguments"), tools);
            Map<String, Object> result = mcpAdapter.callTool(token.token(), toolName, arguments);
            response.put("ok", true);
            response.put("summary", "已调用 Primelayer MCP 工具：" + toolName);
            response.put("toolName", toolName);
            response.put("planner", plan.getOrDefault("planner", "deepseek"));
            response.put("reason", plan.getOrDefault("reason", ""));
            response.put("arguments", arguments);
            response.put("result", result);
            response.put("resultText", extractMcpText(result));
            response.put("plan", plan);
            response.put("toolCount", tools.size());
            response.put("availableTools", compactTools(tools));
        } catch (Exception e) {
            response.put("ok", false);
            response.put("error", sanitizeError(e.getMessage(), token.token()));
        }
        response.put("token", sanitizeToken(token));
        response.put("question", request.question());
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    public Map<String, Object> mockFeishuEvent(DebugDtos.FeishuMockEventRequest request) {
        FeishuIncomingMessage message = feishuEventParser.parse(request.event());
        if (message == null) {
            return Map.of("accepted", false, "parsed", false, "reason", "事件不是可处理的飞书文本消息");
        }
        agentTaskService.createAndPublish(message);
        return Map.of(
                "accepted", true,
                "parsed", true,
                "message", Map.of(
                        "messageId", message.messageId(),
                        "openId", message.openId(),
                        "chatId", message.chatId(),
                        "chatType", message.chatType(),
                        "text", message.text()
                )
        );
    }

    public Map<String, Object> checkFeishuToken() {
        return feishuClient.checkTenantAccessToken();
    }

    public Map<String, Object> testFeishuReply(DebugDtos.FeishuReplyTestRequest request) {
        try {
            String text = hasText(request.text()) ? request.text() : "收到你发送的消息：飞书回复接口测试";
            feishuClient.replyText(request.messageId(), text);
            return Map.of("ok", true, "messageId", request.messageId(), "text", text);
        } catch (Exception e) {
            return Map.of("ok", false, "messageId", request.messageId(), "error", e.getMessage());
        }
    }

    public Map<String, Object> sendFeishuCard(DebugDtos.FeishuCardSendRequest request) {
        try {
            validateReceiveIdType(request.receiveIdType());
            Map<String, Object> response = feishuClient.sendCard(request.receiveIdType(), request.receiveId(), request.card());
            return Map.of(
                    "ok", true,
                    "receiveIdType", request.receiveIdType(),
                    "receiveId", request.receiveId(),
                    "response", response
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "receiveIdType", request.receiveIdType(),
                    "receiveId", request.receiveId(),
                    "error", e.getMessage()
            );
        }
    }

    public Map<String, Object> sendFeishuCardBatch(DebugDtos.FeishuCardBatchSendRequest request) {
        validateReceiveIdType(request.receiveIdType());
        List<String> receiveIds = request.receiveIds() == null ? List.of() : request.receiveIds().stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (receiveIds.isEmpty()) {
            throw new IllegalArgumentException("请选择至少一个接收人");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        for (String receiveId : receiveIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("receiveId", receiveId);
            try {
                item.put("response", feishuClient.sendCard(request.receiveIdType(), receiveId, request.card()));
                item.put("ok", true);
                succeeded++;
            } catch (Exception e) {
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }

        int failed = receiveIds.size() - succeeded;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", failed == 0);
        response.put("receiveIdType", request.receiveIdType());
        response.put("receiveIds", receiveIds);
        response.put("total", receiveIds.size());
        response.put("succeeded", succeeded);
        response.put("failed", failed);
        response.put("results", results);
        return response;
    }

    public Map<String, Object> queryAgent(DebugDtos.AgentQueryRequest request) {
        if (agentServiceClient.isEnabled()) {
            try {
                return queryAgentWithAgentService(request);
            } catch (Exception e) {
                Map<String, Object> fallback = queryAgentLegacy(request);
                fallback.put("agentServiceError", e.getMessage());
                fallback.put("agentServiceFallback", true);
                return fallback;
            }
        }
        return queryAgentLegacy(request);
    }

    private Map<String, Object> queryAgentWithAgentService(DebugDtos.AgentQueryRequest request) {
        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String chatType = defaultChatType(request.chatType());
        String chatId = hasText(request.feishuChatId()) ? request.feishuChatId() : "debug-chat";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("planner", "agent-service");

        TokenResolver.ResolvedContext context = tokenResolver.resolveCandidates(
                request.feishuOpenId(),
                chatId,
                chatType,
                properties.agent().maxProjectsPerQuery()
        );
        if (context.hasError()) {
            response.put("error", context.errorMessage());
            response.put("finalAnswer", context.errorMessage());
            response.put("latencyMs", System.currentTimeMillis() - started);
            return response;
        }

        List<Map<String, Object>> availableTools = loadAvailableTools(context.tokens());
        AgentServiceDtos.AgentAnswerResponse plan = agentServiceClient.answer(
                agentRequest(requestId, request.message(), chatType, request.feishuOpenId(), context, availableTools, List.of())
        );
        response.put("agentPlan", plan);
        response.put("availableTools", availableTools);
        response.put("resolved", Map.of(
                "primelayerUserId", context.primelayerUserId(),
                "projects", context.tokens().stream().map(this::sanitizeTokenEntry).toList()
        ));
        if (plan.requiresClarification()) {
            response.put("finalAnswer", plan.clarificationQuestion());
            response.put("latencyMs", System.currentTimeMillis() - started);
            return response;
        }

        List<Map<String, Object>> toolResults = new ArrayList<>();
        List<AgentServiceDtos.ToolCall> toolCalls = plan.toolCalls() == null ? List.of() : plan.toolCalls();
        for (AgentServiceDtos.ToolCall toolCall : toolCalls) {
            for (TokenResolver.TokenEntry token : targetTokens(context.tokens(), toolCall.projectIds())) {
                toolResults.add(callToolSafely(context.primelayerUserId(), token, toolCall, availableTools));
            }
        }

        String answer = plan.answer();
        AgentServiceDtos.AgentAnswerResponse summary = null;
        if (!hasText(answer) && !toolResults.isEmpty()) {
            summary = agentServiceClient.answer(
                    agentRequest(requestId, request.message(), chatType, request.feishuOpenId(), context, availableTools, toolResults)
            );
            answer = hasText(summary.answer()) ? summary.answer() : deepSeekClient.summarize(request.message(), toolResults);
        }
        response.put("agentSummary", summary);
        response.put("toolResults", toolResults);
        response.put("finalAnswer", hasText(answer) ? answer : "当前没有可执行的 MCP 工具调用计划。");
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> queryAgentLegacy(DebugDtos.AgentQueryRequest request) {
        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        String chatType = defaultChatType(request.chatType());
        String chatId = hasText(request.feishuChatId()) ? request.feishuChatId() : "debug-chat";
        DeepSeekPlan plan = deepSeekClient.plan(requestId, request.message(), chatType);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", requestId);
        response.put("plan", plan);
        if (plan.needClarification()) {
            response.put("finalAnswer", plan.clarificationQuestion());
            response.put("latencyMs", System.currentTimeMillis() - started);
            return response;
        }

        TokenResolver.ResolvedContext context = tokenResolver.resolve(
                request.feishuOpenId(),
                chatId,
                chatType,
                plan,
                properties.agent().maxProjectsPerQuery()
        );
        if (context.hasError()) {
            response.put("error", context.errorMessage());
            response.put("finalAnswer", context.errorMessage());
            response.put("latencyMs", System.currentTimeMillis() - started);
            return response;
        }

        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (TokenResolver.TokenEntry token : context.tokens()) {
            for (DeepSeekPlan.ToolCall toolCall : plan.toolCalls()) {
                Map<String, Object> toolResult = callToolSafely(context.primelayerUserId(), token, toolCall);
                toolResults.add(toolResult);
            }
        }

        String answer = deepSeekClient.summarize(request.message(), toolResults);
        response.put("resolved", Map.of(
                "primelayerUserId", context.primelayerUserId(),
                "projects", context.tokens().stream().map(this::sanitizeTokenEntry).toList()
        ));
        response.put("toolResults", toolResults);
        response.put("finalAnswer", answer);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
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
            return toolRegistry.filterDiscoveredTools(extractTools(mcpAdapter.listTools(tokens.get(0).token())));
        } catch (Exception e) {
            return toolRegistry.defaultToolMaps();
        }
    }

    private Map<String, Object> callToolSafely(String primelayerUserId, TokenResolver.TokenEntry token, DeepSeekPlan.ToolCall toolCall) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", token.projectId());
        result.put("projectName", token.projectName());
        result.put("toolName", toolCall.toolName());
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(toolCall.arguments());
            arguments.put("project_id", token.projectId());
            arguments.put("primelayer_user_id", primelayerUserId);
            toolRegistry.validate(toolCall.toolName(), arguments);
            result.put("status", Status.SUCCEEDED);
            result.put("arguments", arguments);
            result.put("result", mcpAdapter.callTool(token.token(), toolCall.toolName(), arguments));
        } catch (Exception e) {
            result.put("status", Status.FAILED);
            result.put("error", e.getMessage());
        }
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    private Map<String, Object> callToolSafely(
            String primelayerUserId,
            TokenResolver.TokenEntry token,
            AgentServiceDtos.ToolCall toolCall,
            List<Map<String, Object>> availableTools
    ) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", token.projectId());
        result.put("projectName", token.projectName());
        result.put("toolName", toolCall.toolName());
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(toolCall.arguments() == null ? Map.of() : toolCall.arguments());
            arguments.put("project_id", token.projectId());
            arguments.put("primelayer_user_id", primelayerUserId);
            toolRegistry.validate(toolCall.toolName(), arguments, availableTools);
            result.put("status", Status.SUCCEEDED);
            result.put("arguments", arguments);
            result.put("result", mcpAdapter.callTool(token.token(), toolCall.toolName(), arguments));
        } catch (Exception e) {
            result.put("status", Status.FAILED);
            result.put("error", e.getMessage());
        }
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
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

    private List<Map<String, Object>> sanitizeTools(List<Map<String, Object>> tools) {
        return tools.stream().map(tool -> {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("name", tool.get("name"));
            sanitized.put("description", tool.get("description"));
            if (tool.containsKey("inputSchema")) {
                sanitized.put("inputSchema", tool.get("inputSchema"));
            }
            return sanitized;
        }).toList();
    }

    private List<Map<String, Object>> compactTools(List<Map<String, Object>> tools) {
        return tools.stream().map(tool -> {
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", tool.get("name"));
            compact.put("description", tool.get("description"));
            Map<String, Object> properties = inputProperties(tool);
            if (!properties.isEmpty()) {
                compact.put("argumentKeys", properties.keySet());
            }
            Object schema = tool.get("inputSchema");
            if (schema instanceof Map<?, ?> schemaMap && schemaMap.get("required") != null) {
                compact.put("required", schemaMap.get("required"));
            }
            return compact;
        }).toList();
    }

    private Map<String, Object> deterministicMcpPlan(String question, List<Map<String, Object>> tools) {
        String text = question == null ? "" : question.toLowerCase();
        boolean asksAccountInfo = text.contains("当前项目")
                || text.contains("工作空间")
                || text.contains("当前账号")
                || text.contains("账号信息")
                || text.contains("当前用户信息")
                || text.contains("我的信息")
                || text.contains("个人信息")
                || text.contains("租户")
                || text.contains("所属组织")
                || text.contains("account")
                || text.contains("workspace")
                || text.contains("tenant");
        if (asksAccountInfo && hasTool("get_account_info", tools)) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("toolName", "get_account_info");
            plan.put("arguments", Map.of());
            plan.put("reason", "问题询问当前项目、工作空间、用户、租户或组织信息，直接匹配 Primelayer MCP 的 get_account_info。");
            plan.put("planner", "deterministic");
            return plan;
        }
        return Map.of();
    }

    private boolean hasTool(String toolName, List<Map<String, Object>> tools) {
        return tools.stream().anyMatch(tool -> toolName.equals(String.valueOf(tool.get("name"))));
    }

    private void validateDiscoveredTool(String toolName, List<Map<String, Object>> tools) {
        if (!hasText(toolName)) {
            throw new IllegalArgumentException("未能从问题中选择 MCP 工具");
        }
        boolean exists = tools.stream().anyMatch(tool -> toolName.equals(String.valueOf(tool.get("name"))));
        if (!exists) {
            throw new IllegalArgumentException("MCP 服务未暴露工具：" + toolName);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeQuestionArguments(String question, String toolName, Object rawArguments, List<Map<String, Object>> tools) {
        Map<String, Object> arguments = rawArguments instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        Map<String, Object> tool = tools.stream()
                .filter(candidate -> toolName.equals(String.valueOf(candidate.get("name"))))
                .findFirst()
                .orElse(Map.of());
        Map<String, Object> properties = inputProperties(tool);
        putIfSupported(arguments, properties, "question", question);
        putIfSupported(arguments, properties, "query", question);
        putIfSupported(arguments, properties, "prompt", question);
        putIfSupported(arguments, properties, "input", question);
        putIfSupported(arguments, properties, "message", question);
        return arguments;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputProperties(Map<String, Object> tool) {
        Object schema = tool.get("inputSchema");
        if (schema instanceof Map<?, ?> schemaMap) {
            Object properties = schemaMap.get("properties");
            if (properties instanceof Map<?, ?> propertiesMap) {
                return (Map<String, Object>) propertiesMap;
            }
        }
        return Map.of();
    }

    private void putIfSupported(Map<String, Object> arguments, Map<String, Object> properties, String key, String value) {
        if (!arguments.containsKey(key) && properties.containsKey(key)) {
            arguments.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractMcpText(Map<String, Object> response) {
        Object result = response.get("result");
        if (result instanceof Map<?, ?> resultMap) {
            Object content = resultMap.get("content");
            if (content instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .map(item -> String.valueOf(item.getOrDefault("text", item)))
                        .toList()
                        .toString();
            }
        }
        return toJson(response);
    }

    private TokenRecord loadToken(Long tokenId) {
        List<TokenRecord> records = jdbcTemplate.query("""
                select id, primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_hash_suffix
                from project_mcp_token
                where id = ? and token_status = ?
                """, (rs, rowNum) -> new TokenRecord(
                rs.getLong("id"),
                rs.getString("primelayer_user_id"),
                rs.getString("project_id"),
                rs.getString("project_name"),
                cryptoService.decrypt(rs.getString("mcp_token_ciphertext")),
                rs.getString("token_hash_suffix")
        ), tokenId, Status.ACTIVE);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("未找到有效 MCP token 记录：" + tokenId);
        }
        return records.get(0);
    }

    private Map<String, Object> sanitizeToken(TokenRecord token) {
        return Map.of(
                "id", token.id(),
                "primelayerUserId", token.primelayerUserId(),
                "projectId", token.projectId(),
                "projectName", token.projectName(),
                "tokenHashSuffix", token.tokenHashSuffix()
        );
    }

    private Map<String, Object> sanitizeTokenEntry(TokenResolver.TokenEntry token) {
        return Map.of(
                "tokenId", token.tokenId(),
                "projectId", token.projectId(),
                "projectName", token.projectName(),
                "projectRemark", token.projectRemark() == null ? "" : token.projectRemark()
        );
    }

    private Map<String, Object> checkMysql() {
        try {
            Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
            return Map.of("ok", value != null && value == 1);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkRabbit() {
        try (Connection connection = rabbitConnectionFactory.createConnection()) {
            return Map.of("ok", connection.isOpen());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private String defaultChatType(String chatType) {
        return hasText(chatType) ? chatType : "p2p";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateReceiveIdType(String receiveIdType) {
        if (!List.of("open_id", "user_id", "union_id", "email", "chat_id").contains(receiveIdType)) {
            throw new IllegalArgumentException("不支持的 receiveIdType：" + receiveIdType);
        }
    }

    private String sanitizeError(String message, String token) {
        if (message == null) {
            return "MCP 请求失败";
        }
        return token == null || token.isBlank() ? message : message.replace(token, "***REDACTED***");
    }

    @SuppressWarnings("unused")
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private record TokenRecord(
            Long id,
            String primelayerUserId,
            String projectId,
            String projectName,
            String token,
            String tokenHashSuffix
    ) {}
}
