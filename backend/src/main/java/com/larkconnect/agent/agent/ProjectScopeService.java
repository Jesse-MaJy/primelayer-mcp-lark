package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import com.larkconnect.agent.token.TokenResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectScopeService {
    private static final int ALL_PROJECT_LIMIT = 20;
    private final TokenResolver tokens;
    private final DeepSeekConversationClient deepSeek;
    private final ObjectMapper mapper;
    private final ProjectClarificationRepository clarifications;

    public ProjectScopeService(TokenResolver tokens, DeepSeekConversationClient deepSeek, ObjectMapper mapper,
                               ProjectClarificationRepository clarifications) {
        this.tokens = tokens;
        this.deepSeek = deepSeek;
        this.mapper = mapper;
        this.clarifications = clarifications;
    }

    public ScopeResolution resolve(UnifiedQueryService.QueryRequest request,
                                   List<ConversationHistoryService.HistoryTurn> history) {
        var saved = clarifications.loadResolution(request.requestId());
        if (saved.isPresent()) {
            var resolution = saved.get();
            return ScopeResolution.proceed(resolution.question(), resolution.projectIds(), resolution.notice());
        }
        TokenResolver.ResolvedContext catalog = tokens.resolveCatalog(request.openId());
        if (catalog.hasError()) return ScopeResolution.terminal("mcp_config_missing", catalog.errorMessage());
        List<ProjectOption> all = catalog.tokens().stream().map(ProjectOption::from).toList();

        var pending = clarifications.claimNext(request.requestId(), request.openId(), request.chatId());
        if (pending.isPresent()) {
            ProjectClarificationRepository.PendingClarification claimed = pending.get();
            ScopeDecision reply = decide(request.requestId(), request.question(), List.of(), claimed.candidates(), true);
            List<String> confirmedIds = validIds(reply.projectIds(), claimed.candidates());
            if (reply.mode() == DecisionMode.SELECTED && confirmedIds.size() == 1
                    && reply.projectIds().size() == 1) {
                clarifications.complete(claimed.id(), true);
                return saveProceed(request.requestId(), claimed.originalQuestion(), confirmedIds, null);
            }
            clarifications.complete(claimed.id(), false);
        }

        if (all.size() == 1) return saveProceed(request.requestId(), request.question(), List.of(all.get(0).projectId()), null);
        ScopeDecision decision = decide(request.requestId(), request.question(), history, all, false);
        List<String> validIds = validIds(decision.projectIds(), all);
        boolean invalidProjectIds = validIds.size() != decision.projectIds().stream().distinct().count();
        if (decision.mode() == DecisionMode.SELECTED && validIds.size() == 1 && !invalidProjectIds) {
            return saveProceed(request.requestId(), request.question(), validIds, null);
        }
        if (decision.mode() == DecisionMode.CONFIRM || validIds.size() > 1 || invalidProjectIds) {
            List<ProjectOption> candidates = validIds.isEmpty() ? all : all.stream()
                    .filter(project -> validIds.contains(project.projectId())).toList();
            clarifications.save(request.requestId(), request.openId(), request.chatId(), request.question(), candidates);
            return ScopeResolution.clarify(clarificationText(candidates), candidates);
        }
        List<String> selected = all.stream().limit(ALL_PROJECT_LIMIT).map(ProjectOption::projectId).toList();
        String notice = all.size() > ALL_PROJECT_LIMIT
                ? "可用项目共 " + all.size() + " 个；未识别到明确项目，本次按项目名称排序查询前 20 个，另有 "
                    + (all.size() - ALL_PROJECT_LIMIT) + " 个项目未查询。"
                : null;
        return saveProceed(request.requestId(), request.question(), selected, notice);
    }

    private ScopeResolution saveProceed(String requestId, String question, List<String> ids, String notice) {
        clarifications.saveResolution(requestId, question, ids, notice);
        return ScopeResolution.proceed(question, ids, notice);
    }

    private ScopeDecision decide(String requestId, String question,
                                 List<ConversationHistoryService.HistoryTurn> history,
                                 List<ProjectOption> projects, boolean confirmationReply) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", """
                你是项目范围识别器，只输出 JSON，不得回答业务问题。
                mode 只能是 SELECTED、CONFIRM、ALL。
                SELECTED 仅用于唯一且高置信度命中，projectIds 必须且只能有一个。
                CONFIRM 用于一个或多个可能候选但无法唯一确定，projectIds 返回全部候选。
                ALL 仅用于文本和历史中明确没有任何项目线索，projectIds 为空。
                不得返回项目清单之外的 ID。当前是否正在识别用户对候选项目的确认回复：%s。
                可选项目：%s
                输出格式：{"mode":"SELECTED","projectIds":["P1"],"reason":"..."}
                """.formatted(confirmationReply, json(projects))));
        for (ConversationHistoryService.HistoryTurn turn : history) {
            messages.add(Map.of("role", "user", "content", turn.question()));
            messages.add(Map.of("role", "assistant", "content", turn.answer()));
        }
        messages.add(Map.of("role", "user", "content", question));
        DeepSeekConversationClient.Completion response = deepSeek.completeStructured(
                new DeepSeekConversationClient.TraceContext(requestId, null, List.of(), 0,
                        "project_scope", "DeepSeek 项目范围识别"), deepSeek.model(), messages);
        return parse(response.content());
    }

    @SuppressWarnings("unchecked")
    private ScopeDecision parse(String content) {
        try {
            Map<String, Object> value = mapper.readValue(jsonText(content), Map.class);
            DecisionMode mode = DecisionMode.valueOf(String.valueOf(value.getOrDefault("mode", "ALL")).toUpperCase());
            List<String> ids = value.get("projectIds") instanceof List<?> list
                    ? list.stream().map(String::valueOf).filter(id -> !id.isBlank()).distinct().toList()
                    : List.of();
            String reason = String.valueOf(value.getOrDefault("reason", ""));
            return new ScopeDecision(mode, ids, reason);
        } catch (Exception ignored) {
            return new ScopeDecision(DecisionMode.CONFIRM, List.of(), "项目范围识别响应无效");
        }
    }

    private List<String> validIds(List<String> requested, List<ProjectOption> projects) {
        Set<String> allowed = projects.stream().map(ProjectOption::projectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return requested.stream().filter(allowed::contains).distinct().toList();
    }

    private String clarificationText(List<ProjectOption> projects) {
        StringBuilder text = new StringBuilder("我识别到多个可能的项目，请回复其中一个项目名称以继续原问题：\n");
        projects.forEach(project -> text.append("- ").append(project.projectName())
                .append("（").append(project.projectId()).append("）")
                .append(project.projectRemark().isBlank() ? "" : "，备注：" + project.projectRemark())
                .append('\n'));
        return text.toString().trim();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法序列化项目清单", e); }
    }

    private String jsonText(String value) {
        if (value == null) return "{}";
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : "{}";
    }

    enum DecisionMode { SELECTED, CONFIRM, ALL }
    record ScopeDecision(DecisionMode mode, List<String> projectIds, String reason) {}

    public record ProjectOption(String projectId, String projectName, String projectRemark) {
        static ProjectOption from(TokenResolver.TokenEntry token) {
            return new ProjectOption(token.projectId(), token.projectName(), token.projectRemark());
        }
    }

    public record ScopeResolution(String terminalPath, String effectiveQuestion, List<String> projectIds,
                                  String notice, String answer, List<ProjectOption> candidates) {
        public boolean terminal() { return terminalPath != null; }
        public boolean clarification() { return "project_scope_clarification".equals(terminalPath); }
        static ScopeResolution proceed(String question, List<String> ids, String notice) {
            return new ScopeResolution(null, question, List.copyOf(ids), notice, null, List.of());
        }
        static ScopeResolution clarify(String answer, List<ProjectOption> candidates) {
            return new ScopeResolution("project_scope_clarification", null,
                    candidates.stream().map(ProjectOption::projectId).toList(),
                    null, answer, List.copyOf(candidates));
        }
        static ScopeResolution terminal(String path, String answer) {
            return new ScopeResolution(path, null, List.of(), null, answer, List.of());
        }
    }
}
