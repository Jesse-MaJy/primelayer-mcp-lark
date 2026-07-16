package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import com.larkconnect.agent.token.TokenResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectScopeServiceTest {
    private final TokenResolver tokens = mock(TokenResolver.class);
    private final DeepSeekConversationClient deepSeek = mock(DeepSeekConversationClient.class);
    private final ProjectClarificationRepository pending = mock(ProjectClarificationRepository.class);
    private final ProjectScopeService service = new ProjectScopeService(tokens, deepSeek, new ObjectMapper(), pending);

    @Test
    void singleEligibleTokenIsDefaultWithoutModelCall() {
        when(pending.loadResolution("r1")).thenReturn(Optional.empty());
        when(pending.claimNext("r1", "u1", "c1")).thenReturn(Optional.empty());
        when(tokens.resolveCatalog("u1")).thenReturn(catalog(project("P1", "项目一")));

        ProjectScopeService.ScopeResolution result = service.resolve(request("r1", "查询任务"), List.of());

        assertThat(result.projectIds()).containsExactly("P1");
        verifyNoInteractions(deepSeek);
        verify(pending).saveResolution("r1", "查询任务", List.of("P1"), null);
    }

    @Test
    void missingOpenIdTokenStopsBeforeModelAndMcpDiscovery() {
        when(pending.loadResolution("missing")).thenReturn(Optional.empty());
        when(tokens.resolveCatalog("u1")).thenReturn(TokenResolver.ResolvedContext.error("没有可用 Token"));

        ProjectScopeService.ScopeResolution result = service.resolve(request("missing", "查询任务"), List.of());

        assertThat(result.terminalPath()).isEqualTo("mcp_config_missing");
        assertThat(result.answer()).isEqualTo("没有可用 Token");
        verifyNoInteractions(deepSeek);
    }

    @Test
    void noProjectClueFallsBackToNameSortedFirstTwentyAndDisclosesTruncation() {
        when(pending.loadResolution("r2")).thenReturn(Optional.empty());
        when(pending.claimNext("r2", "u1", "c1")).thenReturn(Optional.empty());
        List<TokenResolver.TokenEntry> projects = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> project("P" + String.format("%02d", i), "项目" + String.format("%02d", i))).toList();
        when(tokens.resolveCatalog("u1")).thenReturn(catalog(projects.toArray(TokenResolver.TokenEntry[]::new)));
        when(deepSeek.model()).thenReturn("deepseek-v4-pro");
        when(deepSeek.completeStructured(any(), any(), any())).thenReturn(completion("{\"mode\":\"ALL\",\"projectIds\":[]}"));

        ProjectScopeService.ScopeResolution result = service.resolve(request("r2", "上个月有哪些作业票"), List.of());

        assertThat(result.projectIds()).hasSize(20).doesNotContain("P21");
        assertThat(result.notice()).contains("共 21 个", "另有 1 个项目未查询");
    }

    @Test
    void ambiguousModelDecisionPersistsCandidatesForNextMessage() {
        when(pending.loadResolution("r3")).thenReturn(Optional.empty());
        when(pending.claimNext("r3", "u1", "c1")).thenReturn(Optional.empty());
        when(tokens.resolveCatalog("u1")).thenReturn(catalog(project("P1", "一期"), project("P2", "二期")));
        when(deepSeek.model()).thenReturn("deepseek-v4-pro");
        when(deepSeek.completeStructured(any(), any(), any())).thenReturn(
                completion("{\"mode\":\"CONFIRM\",\"projectIds\":[\"P1\",\"P2\"]}"));

        ProjectScopeService.ScopeResolution result = service.resolve(request("r3", "查询一期和二期"), List.of());

        assertThat(result.clarification()).isTrue();
        assertThat(result.answer()).contains("一期", "二期");
        verify(pending).save(eq("r3"), eq("u1"), eq("c1"), eq("查询一期和二期"), any());
    }

    @Test
    void uniqueReplyConsumesPendingAndResumesOriginalQuestion() {
        when(pending.loadResolution("r4")).thenReturn(Optional.empty());
        when(tokens.resolveCatalog("u1")).thenReturn(catalog(project("P1", "一期"), project("P2", "二期")));
        var options = List.of(new ProjectScopeService.ProjectOption("P1", "一期", ""),
                new ProjectScopeService.ProjectOption("P2", "二期", ""));
        when(pending.claimNext("r4", "u1", "c1")).thenReturn(Optional.of(
                new ProjectClarificationRepository.PendingClarification(9L, "查询上月作业票", options)));
        when(deepSeek.model()).thenReturn("deepseek-v4-pro");
        when(deepSeek.completeStructured(any(), any(), any())).thenReturn(
                completion("{\"mode\":\"SELECTED\",\"projectIds\":[\"P2\"]}"));

        ProjectScopeService.ScopeResolution result = service.resolve(request("r4", "二期"), List.of());

        assertThat(result.effectiveQuestion()).isEqualTo("查询上月作业票");
        assertThat(result.projectIds()).containsExactly("P2");
        verify(pending).complete(9L, true);
    }

    private UnifiedQueryService.QueryRequest request(String id, String question) {
        return new UnifiedQueryService.QueryRequest(id, question, "p2p", "u1", "c1");
    }

    private TokenResolver.ResolvedContext catalog(TokenResolver.TokenEntry... entries) {
        return TokenResolver.ResolvedContext.ok(null, List.of(entries));
    }

    private TokenResolver.TokenEntry project(String id, String name) {
        return new TokenResolver.TokenEntry(1L, "u1", "mcp-user", id, name, "", "token-" + id);
    }

    private DeepSeekConversationClient.Completion completion(String content) {
        return new DeepSeekConversationClient.Completion(content, List.of(), Map.of(), 0, 0);
    }
}
