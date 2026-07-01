package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.ai.AiEngineType;
import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.ai.AnswerEngineRouter;
import com.larkconnect.agent.ai.FastGptAnswerEngine;
import com.larkconnect.agent.ai.FastGptClient;
import com.larkconnect.agent.audit.AuditService;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.common.Status;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.deepseek.DeepSeekClient;
import com.larkconnect.agent.feishu.FeishuClient;
import com.larkconnect.agent.mcp.McpAdapter;
import com.larkconnect.agent.mcp.McpToolRegistry;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorTest {
    @Test
    void usesFastGptForNormalQuestionsWhenSelectedAndSkipsLocalMcpFlow() {
        FakeTaskService taskService = new FakeTaskService(task("Roche今日施工情况"));
        FakeTokenResolver tokenResolver = new FakeTokenResolver(TokenResolver.ResolvedContext.ok("pl-user", List.of(token())));
        FakeMcpAdapter mcpAdapter = new FakeMcpAdapter();
        FakeFeishuClient feishuClient = new FakeFeishuClient();
        FakeAgentServiceClient agentServiceClient = new FakeAgentServiceClient();
        FakeFastGptClient fastGptClient = new FakeFastGptClient("FastGPT 已回答");
        FakeAuditService auditService = new FakeAuditService();

        orchestrator(
                taskService,
                new FakeDeepSeekClient(),
                tokenResolver,
                mcpAdapter,
                feishuClient,
                agentServiceClient,
                router(AiEngineType.FASTGPT, fastGptClient),
                auditService
        ).process("req-fastgpt");

        assertThat(fastGptClient.requests).hasSize(1);
        assertThat(fastGptClient.requests.get(0).question()).isEqualTo("Roche今日施工情况");
        assertThat(fastGptClient.requests.get(0).chatId()).isEqualTo("open-1");
        assertThat(mcpAdapter.calledTools).isEmpty();
        assertThat(agentServiceClient.requests).isZero();
        assertThat(feishuClient.lastAnswer).isEqualTo("FastGPT 已回答");
        assertThat(auditService.mainIntents).containsExactly("fastgpt_answer");
        assertThat(auditService.modelPurposes).containsExactly("fastgpt_answer");
    }

    @Test
    void fallsBackToLocalAgentWhenFastGptFails() {
        FakeTaskService taskService = new FakeTaskService(task("Roche今日施工情况"));
        FakeTokenResolver tokenResolver = new FakeTokenResolver(TokenResolver.ResolvedContext.ok("pl-user", List.of(token())));
        FakeMcpAdapter mcpAdapter = new FakeMcpAdapter();
        mcpAdapter.tools = tools("get_report");
        mcpAdapter.results.put("get_report", Map.of("content", "施工正常"));
        FakeFeishuClient feishuClient = new FakeFeishuClient();
        FakeAgentServiceClient agentServiceClient = new FakeAgentServiceClient();
        agentServiceClient.responses.add(answerWithCalls(new AgentServiceDtos.ToolCall("get_report", Map.of(), List.of("Roche"), "日报")));
        agentServiceClient.responses.add(finalAnswer("本地 agent 已回答"));
        FakeFastGptClient fastGptClient = new FakeFastGptClient(new IllegalStateException("FastGPT timeout"));
        FakeAuditService auditService = new FakeAuditService();

        orchestrator(
                taskService,
                new FakeDeepSeekClient(),
                tokenResolver,
                mcpAdapter,
                feishuClient,
                agentServiceClient,
                router(AiEngineType.FASTGPT, fastGptClient),
                auditService
        ).process("req-fallback");

        assertThat(fastGptClient.requests).hasSize(1);
        assertThat(mcpAdapter.calledTools).containsExactly("get_report");
        assertThat(feishuClient.lastAnswer).isEqualTo("本地 agent 已回答");
        assertThat(auditService.modelPurposes).contains("fastgpt_fallback");
    }

    @Test
    void keepsMcpConfigStatusLocalWhenFastGptIsSelected() {
        FakeTaskService taskService = new FakeTaskService(task("配置正常"));
        FakeTokenResolver tokenResolver = new FakeTokenResolver(TokenResolver.ResolvedContext.ok("pl-user", List.of(token())));
        tokenResolver.checkResult = TokenResolver.McpConfigCheckResult.configured("open-1", "pl-user", "OPEN_ID", "open-1", List.of(new TokenResolver.ProjectRef("Roche", "Roche")));
        FakeMcpAdapter mcpAdapter = new FakeMcpAdapter();
        mcpAdapter.tools = tools("get_account_info");
        FakeFeishuClient feishuClient = new FakeFeishuClient();
        FakeFastGptClient fastGptClient = new FakeFastGptClient("FastGPT should not answer");

        orchestrator(
                taskService,
                new FakeDeepSeekClient(),
                tokenResolver,
                mcpAdapter,
                feishuClient,
                new FakeAgentServiceClient(),
                router(AiEngineType.FASTGPT, fastGptClient),
                new FakeAuditService()
        ).process("req-config");

        assertThat(fastGptClient.requests).isEmpty();
        assertThat(feishuClient.lastTitle).isEqualTo("MCP 配置状态");
        assertThat(feishuClient.lastAnswer).contains("MCP 配置已绑定");
    }

    @Test
    void executesAgentServiceToolCallsUntilFinalAnswer() {
        FakeTaskService taskService = new FakeTaskService(task("Roche今日施工情况"));
        FakeTokenResolver tokenResolver = new FakeTokenResolver(TokenResolver.ResolvedContext.ok("pl-user", List.of(token())));
        FakeMcpAdapter mcpAdapter = new FakeMcpAdapter();
        mcpAdapter.tools = tools("get_report", "get_async_task_result");
        mcpAdapter.results.put("get_report", Map.of("taskId", "task-123"));
        mcpAdapter.results.put("get_async_task_result", Map.of("content", "施工正常"));
        FakeFeishuClient feishuClient = new FakeFeishuClient();
        FakeDeepSeekClient deepSeekClient = new FakeDeepSeekClient();
        FakeAgentServiceClient agentServiceClient = new FakeAgentServiceClient();
        agentServiceClient.responses.add(answerWithCalls(new AgentServiceDtos.ToolCall("get_report", Map.of("reportType", "DAILY"), List.of("Roche"), "日报")));
        agentServiceClient.responses.add(answerWithCalls(new AgentServiceDtos.ToolCall("get_async_task_result", Map.of("taskId", "task-123"), List.of("Roche"), "轮询")));
        agentServiceClient.responses.add(finalAnswer("项目报告已生成"));

        orchestrator(taskService, deepSeekClient, tokenResolver, mcpAdapter, feishuClient, agentServiceClient).process("req-1");

        assertThat(mcpAdapter.calledTools).containsExactly("get_report", "get_async_task_result");
        assertThat(feishuClient.lastMessageId).isEqualTo("msg-1");
        assertThat(feishuClient.lastQuestion).isEqualTo("Roche今日施工情况");
        assertThat(feishuClient.lastAnswer).isEqualTo("项目报告已生成");
        assertThat(feishuClient.lastTitle).isEqualTo("项目查询");
        assertThat(deepSeekClient.summarizeCalls).isZero();
    }

    @Test
    void repliesBusinessUnavailableCopyWhenProjectContextCannotResolve() {
        FakeTaskService taskService = new FakeTaskService(task("Roche今日施工情况"));
        FakeTokenResolver tokenResolver = new FakeTokenResolver(TokenResolver.ResolvedContext.error("当前飞书 open_id 下没有 ACTIVE MCP Token。请在「人员配置」中配置。"));
        tokenResolver.checkResult = TokenResolver.McpConfigCheckResult.missing("open-1", null, null, null, "缺少项目访问配置");
        FakeFeishuClient feishuClient = new FakeFeishuClient();

        orchestrator(taskService, new FakeDeepSeekClient(), tokenResolver, new FakeMcpAdapter(), feishuClient, new FakeAgentServiceClient()).process("req-2");

        assertThat(feishuClient.lastTitle).isEqualTo("项目数据暂不可用");
        assertThat(feishuClient.lastTemplate).isEqualTo("orange");
        assertThat(feishuClient.lastAnswer).contains("暂时无法访问项目数据");
        assertThat(feishuClient.lastAnswer).doesNotContain("人员配置");
        assertThat(feishuClient.lastAnswer).doesNotContain("MCP Token");
    }

    private AgentOrchestrator orchestrator(
            AgentTaskService taskService,
            DeepSeekClient deepSeekClient,
            TokenResolver tokenResolver,
            McpAdapter mcpAdapter,
            FeishuClient feishuClient,
            AgentServiceClient agentServiceClient
    ) {
        return orchestrator(
                taskService,
                deepSeekClient,
                tokenResolver,
                mcpAdapter,
                feishuClient,
                agentServiceClient,
                router(AiEngineType.LOCAL_AGENT, new FakeFastGptClient("unused")),
                new FakeAuditService()
        );
    }

    private AgentOrchestrator orchestrator(
            AgentTaskService taskService,
            DeepSeekClient deepSeekClient,
            TokenResolver tokenResolver,
            McpAdapter mcpAdapter,
            FeishuClient feishuClient,
            AgentServiceClient agentServiceClient,
            AnswerEngineRouter answerEngineRouter,
            AuditService auditService
    ) {
        return new AgentOrchestrator(
                taskService,
                deepSeekClient,
                tokenResolver,
                new McpToolRegistry(),
                mcpAdapter,
                feishuClient,
                auditService,
                properties(),
                new ObjectMapper(),
                agentServiceClient,
                new IntentRouter(),
                answerEngineRouter,
                new ChainTraceService(null, new ObjectMapper())
        );
    }

    private AnswerEngineRouter router(AiEngineType engineType, FastGptClient fastGptClient) {
        return new AnswerEngineRouter(new FakeAiRuntimeConfigService(engineType), new FastGptAnswerEngine(fastGptClient));
    }

    private static Map<String, Object> task(String question) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("message_text", question);
        task.put("feishu_message_id", "msg-1");
        task.put("feishu_chat_id", "chat-1");
        task.put("feishu_open_id", "open-1");
        task.put("chat_type", "p2p");
        return task;
    }

    private static TokenResolver.TokenEntry token() {
        return new TokenResolver.TokenEntry(1L, "Roche", "Roche", "罗诊", "token");
    }

    private static AgentServiceDtos.AgentAnswerResponse answerWithCalls(AgentServiceDtos.ToolCall call) {
        return new AgentServiceDtos.AgentAnswerResponse(false, null, "project_report", "single_project", List.of("Roche"), List.of(call), null, Map.of());
    }

    private static AgentServiceDtos.AgentAnswerResponse finalAnswer(String answer) {
        return new AgentServiceDtos.AgentAnswerResponse(false, null, "project_report", "single_project", List.of("Roche"), List.of(), answer, Map.of());
    }

    private static Map<String, Object> tools(String... names) {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (String name : names) {
            toolList.add(Map.of(
                    "name", name,
                    "description", name,
                    "inputSchema", Map.of("type", "object", "properties", Map.of())
            ));
        }
        return Map.of("result", Map.of("tools", toolList));
    }

    private static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(5, 30000, 30000),
                new AppProperties.AgentService(true, "http://localhost:8090"),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("https://api.deepseek.com", "key", "deepseek-chat"),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key")
        );
    }

    private static class FakeTaskService extends AgentTaskService {
        private final Map<String, Object> task;

        FakeTaskService(Map<String, Object> task) {
            super(null, null);
            this.task = task;
        }

        @Override
        public Map<String, Object> loadTask(String requestId) {
            return task;
        }
    }

    private static class FakeTokenResolver extends TokenResolver {
        private final ResolvedContext context;
        private McpConfigCheckResult checkResult = McpConfigCheckResult.configured("open-1", "pl-user", "OPEN_ID", "open-1", List.of(new ProjectRef("Roche", "Roche")));

        FakeTokenResolver(ResolvedContext context) {
            super(null, null);
            this.context = context;
        }

        @Override
        public ResolvedContext resolveCandidates(String openId, String chatId, String chatType, int maxProjects) {
            return context;
        }

        @Override
        public McpConfigCheckResult checkMcpConfig(String openId, String chatId, String chatType, int maxProjects) {
            return checkResult;
        }

        @Override
        public void updateVerificationStatus(Long tokenId, String verifyStatus, String verifyError) {
        }
    }

    private static class FakeMcpAdapter extends McpAdapter {
        private Map<String, Object> tools = tools();
        private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        private final List<String> calledTools = new ArrayList<>();

        FakeMcpAdapter() {
            super(properties(), RestClient.builder(), new ObjectMapper());
        }

        @Override
        public Map<String, Object> listTools(String token) {
            return tools;
        }

        @Override
        public Map<String, Object> callTool(String token, String toolName, Map<String, Object> arguments) {
            calledTools.add(toolName);
            return results.getOrDefault(toolName, Map.of());
        }
    }

    private static class FakeFeishuClient extends FeishuClient {
        private String lastMessageId;
        private String lastQuestion;
        private String lastAnswer;
        private String lastTitle;
        private String lastTemplate;

        FakeFeishuClient() {
            super(properties(), new ObjectMapper(), RestClient.builder());
        }

        @Override
        public void replyAnswerCard(String messageId, String question, String answer, String title, String template) {
            this.lastMessageId = messageId;
            this.lastQuestion = question;
            this.lastAnswer = answer;
            this.lastTitle = title;
            this.lastTemplate = template;
        }
    }

    private static class FakeDeepSeekClient extends DeepSeekClient {
        private int summarizeCalls;

        FakeDeepSeekClient() {
            super(properties(), new ObjectMapper(), RestClient.builder(), new McpToolRegistry());
        }

        @Override
        public String summarize(String question, List<Map<String, Object>> toolResults) {
            summarizeCalls++;
            return "fallback";
        }
    }

    private static class FakeAgentServiceClient extends AgentServiceClient {
        private final Deque<AgentServiceDtos.AgentAnswerResponse> responses = new ArrayDeque<>();
        private int requests;

        FakeAgentServiceClient() {
            super(properties(), RestClient.builder());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public AgentServiceDtos.AgentAnswerResponse answer(AgentServiceDtos.AgentAnswerRequest request) {
            requests++;
            return responses.isEmpty() ? finalAnswer("默认回答") : responses.removeFirst();
        }
    }

    private static class FakeAiRuntimeConfigService extends AiRuntimeConfigService {
        private final AiSettings settings;

        FakeAiRuntimeConfigService(AiEngineType engineType) {
            super(null, null);
            this.settings = new AiSettings(
                    engineType,
                    "http://fastgpt.local",
                    "fastgpt",
                    "fastgpt-key",
                    true,
                    30000,
                    true
            );
        }

        @Override
        public AiSettings loadSettings() {
            return settings;
        }
    }

    private static class FakeFastGptClient extends FastGptClient {
        private final List<FastGptRequest> requests = new ArrayList<>();
        private final String answer;
        private final RuntimeException failure;

        FakeFastGptClient(String answer) {
            super(null, null, null);
            this.answer = answer;
            this.failure = null;
        }

        FakeFastGptClient(RuntimeException failure) {
            super(null, null, null);
            this.answer = null;
            this.failure = failure;
        }

        @Override
        public FastGptResponse answer(FastGptRequest request, AiRuntimeConfigService.AiSettings settings) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return new FastGptResponse(answer, "raw", 12);
        }
    }

    private static class FakeAuditService extends AuditService {
        private final List<String> mainIntents = new ArrayList<>();
        private final List<String> modelPurposes = new ArrayList<>();

        FakeAuditService() {
            super(null, new ObjectMapper());
        }

        @Override
        public void writeMain(String requestId, String openId, String chatId, String primelayerUserId, List<String> projectIds, String question, String intent, String answer, long latencyMs, String error) {
            mainIntents.add(intent);
        }

        @Override
        public void writeTool(String requestId, String projectId, String primelayerUserId, String toolName, Map<String, Object> arguments, String status, long latencyMs, String error) {
        }

        @Override
        public void writeModel(String requestId, String model, String purpose, String inputSummary, String outputText, String status, long latencyMs, String error) {
            assertThat(status).isIn(Status.SUCCEEDED, Status.FAILED);
            modelPurposes.add(purpose);
        }
    }
}
