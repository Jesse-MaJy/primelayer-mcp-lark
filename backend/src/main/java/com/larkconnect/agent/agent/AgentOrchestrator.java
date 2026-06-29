package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.deepseek.DeepSeekClient;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.mcp.McpToolRegistry;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public AgentOrchestrator(
            AgentTaskService taskService,
            DeepSeekClient deepSeekClient,
            TokenResolver tokenResolver,
            McpToolRegistry toolRegistry,
            McpAdapter mcpAdapter,
            FeishuClient feishuClient,
            AuditService auditService,
            AppProperties properties,
            ObjectMapper objectMapper
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
    }

    public void process(String requestId) {
        long started = System.currentTimeMillis();
        Map<String, Object> task = taskService.loadTask(requestId);
        String question = task.get("message_text").toString();
        String chatId = task.get("feishu_chat_id").toString();
        String openId = task.get("feishu_open_id").toString();
        String chatType = task.get("chat_type").toString();
        feishuClient.sendText(chatId, "正在分析，请稍等。");

        DeepSeekPlan plan = deepSeekClient.plan(requestId, question, chatType);
        auditService.writeModel(requestId, properties.deepseek().model(), "plan", question, toJson(plan), Status.SUCCEEDED, 0, null);
        if (plan.needClarification()) {
            feishuClient.sendText(chatId, plan.clarificationQuestion());
            auditService.writeMain(requestId, openId, chatId, null, List.of(), question, plan.intent(), plan.clarificationQuestion(), System.currentTimeMillis() - started, null);
            return;
        }

        TokenResolver.ResolvedContext context = tokenResolver.resolve(openId, chatId, chatType, plan, properties.agent().maxProjectsPerQuery());
        if (context.hasError()) {
            feishuClient.sendText(chatId, context.errorMessage());
            auditService.writeMain(requestId, openId, chatId, null, List.of(), question, plan.intent(), context.errorMessage(), System.currentTimeMillis() - started, context.errorMessage());
            return;
        }

        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (TokenResolver.TokenEntry token : context.tokens()) {
            for (DeepSeekPlan.ToolCall toolCall : plan.toolCalls()) {
                long toolStarted = System.currentTimeMillis();
                try {
                    toolRegistry.validate(toolCall.toolName(), toolCall.arguments());
                    Map<String, Object> args = new java.util.LinkedHashMap<>(toolCall.arguments());
                    args.put("project_id", token.projectId());
                    args.put("primelayer_user_id", context.primelayerUserId());
                    Map<String, Object> result = mcpAdapter.callTool(token.token(), toolCall.toolName(), args);
                    toolResults.add(Map.of("projectId", token.projectId(), "projectName", token.projectName(), "status", Status.SUCCEEDED, "result", result));
                    auditService.writeTool(requestId, token.projectId(), context.primelayerUserId(), toolCall.toolName(), args, Status.SUCCEEDED, System.currentTimeMillis() - toolStarted, null);
                } catch (Exception e) {
                    toolResults.add(Map.of("projectId", token.projectId(), "projectName", token.projectName(), "status", Status.FAILED, "error", e.getMessage()));
                    auditService.writeTool(requestId, token.projectId(), context.primelayerUserId(), toolCall.toolName(), toolCall.arguments(), Status.FAILED, System.currentTimeMillis() - toolStarted, e.getMessage());
                }
            }
        }

        String answer = deepSeekClient.summarize(question, toolResults);
        auditService.writeModel(requestId, properties.deepseek().model(), "summarize", "toolResults=" + toolResults.size(), answer, Status.SUCCEEDED, 0, null);
        feishuClient.sendText(chatId, answer);
        auditService.writeMain(
                requestId,
                openId,
                chatId,
                context.primelayerUserId(),
                context.tokens().stream().map(TokenResolver.TokenEntry::projectId).toList(),
                question,
                plan.intent(),
                answer,
                System.currentTimeMillis() - started,
                null
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
