package com.larkconnect.agent.mcp;

import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

@Service
public class DefaultMcpQueryGateway implements McpQueryGateway {
    private final TokenResolver tokenResolver;
    private final McpAdapter mcpAdapter;
    private final McpToolRegistry toolRegistry;
    private final AuditService auditService;
    private final AppProperties properties;
    private final Map<QueryContext, List<TokenResolver.TokenEntry>> authorizedTokens =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<QueryContext, Map<String, List<Map<String, Object>>>> projectTools =
            Collections.synchronizedMap(new WeakHashMap<>());

    public DefaultMcpQueryGateway(TokenResolver tokenResolver, McpAdapter mcpAdapter,
                                  McpToolRegistry toolRegistry, AuditService auditService,
                                  AppProperties properties) {
        this.tokenResolver = tokenResolver;
        this.mcpAdapter = mcpAdapter;
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Override
    public QueryContext loadContext(String openId, String chatId, String chatType) {
        TokenResolver.ResolvedContext resolved = tokenResolver.resolveCandidates(
                openId, chatId, chatType, properties.agent().maxProjectsPerQuery());
        if (resolved.hasError()) return new QueryContext(null, List.of(), List.of(), resolved.errorMessage());
        List<Project> projects = resolved.tokens().stream()
                .map(token -> new Project(token.projectId(), token.projectName()))
                .toList();
        Map<String, List<Map<String, Object>>> toolsByProject = new LinkedHashMap<>();
        List<String> discoveryFailures = new ArrayList<>();
        for (TokenResolver.TokenEntry token : resolved.tokens()) {
            try {
                toolsByProject.put(token.projectId(), toolRegistry.filterDiscoveredTools(
                        extractTools(mcpAdapter.listTools(token.token()))));
            } catch (Exception e) {
                toolsByProject.put(token.projectId(), List.of());
                discoveryFailures.add(token.projectName() + "：" + readable(e));
            }
        }
        List<Map<String, Object>> tools = exposeProjectSpecificTools(toolsByProject);
        String availabilityError = !discoveryFailures.isEmpty()
                ? "部分项目 MCP 工具发现失败：" + String.join("；", discoveryFailures)
                : tools.isEmpty() ? "MCP 没有暴露可用的只读工具" : null;
        QueryContext context = new QueryContext(resolved.primelayerUserId(), projects, tools, availabilityError);
        authorizedTokens.put(context, List.copyOf(resolved.tokens()));
        projectTools.put(context, immutableProjectTools(toolsByProject));
        return context;
    }

    @Override
    public List<ToolObservation> execute(String requestId, QueryContext context, String toolName,
                                         List<String> projectIds, Map<String, Object> arguments) {
        List<TokenResolver.TokenEntry> tokens = authorizedTokens.getOrDefault(context, List.of());
        if (tokens.isEmpty()) throw new IllegalArgumentException("MCP 项目上下文已失效，请重试");
        List<TokenResolver.TokenEntry> targets = targetTokens(tokens, projectIds);
        Map<String, List<Map<String, Object>>> toolsByProject = projectTools.getOrDefault(context, Map.of());
        List<ToolObservation> observations = new ArrayList<>();
        for (TokenResolver.TokenEntry token : targets) {
            List<Map<String, Object>> targetTools = toolsByProject.getOrDefault(token.projectId(), List.of());
            try {
                toolRegistry.validate(toolName, arguments, targetTools);
                McpAdapter.PaginationStyle paginationStyle = paginationStyle(toolName, arguments, targetTools);
                observations.add(executeForProject(requestId, context.primelayerUserId(), token,
                        toolName, arguments, paginationStyle));
            } catch (Exception e) {
                String error = "项目 " + token.projectName() + " / " + toolName + " 校验失败：" + readable(e);
                auditService.writeTool(requestId, token.projectId(), context.primelayerUserId(), toolName,
                        arguments, Status.FAILED, 0, error);
                observations.add(new ToolObservation(token.projectId(), token.projectName(), toolName,
                        Status.FAILED, Map.of(), error, 0, 0, false));
            }
        }
        return observations;
    }

    private ToolObservation executeForProject(String requestId, String primelayerUserId,
                                              TokenResolver.TokenEntry token, String toolName,
                                              Map<String, Object> requestedArguments,
                                              McpAdapter.PaginationStyle paginationStyle) {
        Map<String, Object> arguments = new LinkedHashMap<>(requestedArguments == null ? Map.of() : requestedArguments);
        arguments.put("project_id", token.projectId());
        arguments.put("primelayer_user_id", primelayerUserId);
        long started = System.currentTimeMillis();
        try {
            if (paginationStyle == null) {
                Map<String, Object> response = mcpAdapter.callTool(token.token(), toolName, arguments);
                long latency = System.currentTimeMillis() - started;
                auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolName, arguments, Status.SUCCEEDED, latency, null);
                return new ToolObservation(token.projectId(), token.projectName(), toolName, Status.SUCCEEDED,
                        Map.of("result", response), null, 1, 1, false);
            }
            McpAdapter.PaginationResult result = mcpAdapter.callToolWithPagination(
                    token.token(), toolName, arguments, 100, paginationStyle);
            for (McpAdapter.PageData page : result.pages()) {
                auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolName, arguments,
                        page.status(), page.latencyMs(), page.error());
            }
            List<Map<String, Object>> pages = result.pages().stream()
                    .filter(page -> Status.SUCCEEDED.equals(page.status()) && page.rawResponse() != null)
                    .map(McpAdapter.PageData::rawResponse).toList();
            boolean truncated = result.limitReached();
            String status = result.successPages() == 0 ? Status.FAILED
                    : result.failedPages() > 0 || truncated ? "PARTIAL" : Status.SUCCEEDED;
            String error = result.failedPages() > 0 ? "项目 " + token.projectName() + " 的部分分页查询失败"
                    : truncated ? "项目 " + token.projectName() + " 自动分页达到 50 页安全上限" : null;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("pages", pages);
            payload.put("totalCount", result.totalCount());
            payload.put("pageLimitReached", truncated);
            return new ToolObservation(token.projectId(), token.projectName(), toolName, status, payload, error,
                    result.pages().size(), result.pages().size(), truncated);
        } catch (Exception e) {
            String error = "项目 " + token.projectName() + " / " + toolName + " 查询失败：" + readable(e);
            auditService.writeTool(requestId, token.projectId(), primelayerUserId, toolName, arguments,
                    Status.FAILED, System.currentTimeMillis() - started, error);
            return new ToolObservation(token.projectId(), token.projectName(), toolName, Status.FAILED,
                    Map.of(), error, 1, 0, false);
        }
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
