package com.larkconnect.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProjectClarificationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProjectClarificationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(String requestId, String openId, String chatId, String question,
                     List<ProjectScopeService.ProjectOption> candidates) {
        Long taskId = jdbc.queryForObject("select id from agent_task where request_id=?", Long.class, requestId);
        jdbc.update("""
                insert into agent_project_scope_clarification(
                  scope_key, feishu_open_id, feishu_chat_id, original_request_id, original_task_id,
                  original_question, candidate_projects, status)
                values (?, ?, ?, ?, ?, ?, cast(? as json), 'PENDING')
                on duplicate key update original_request_id=values(original_request_id),
                  original_task_id=values(original_task_id), original_question=values(original_question),
                  candidate_projects=values(candidate_projects), status='PENDING', claimed_by_request_id=null
                """, scopeKey(openId, chatId), openId, safe(chatId), requestId, taskId, question, json(candidates));
    }

    @Transactional
    public Optional<PendingClarification> claimNext(String currentRequestId, String openId, String chatId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.original_question, c.candidate_projects
                from agent_project_scope_clarification c
                join agent_task current_task on current_task.request_id = ?
                where c.scope_key = ? and c.status = 'PENDING'
                  and c.original_task_id < current_task.id
                  and not exists (
                    select 1 from agent_task between_task
                    where between_task.feishu_open_id = ?
                      and coalesce(between_task.feishu_chat_id, '') = ?
                      and between_task.id > c.original_task_id
                      and between_task.id < current_task.id
                  )
                for update
                """, currentRequestId, scopeKey(openId, chatId), openId, safe(chatId));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        int updated = jdbc.update("""
                update agent_project_scope_clarification
                set status='CLAIMED', claimed_by_request_id=? where id=? and status='PENDING'
                """, currentRequestId, id);
        if (updated != 1) return Optional.empty();
        return Optional.of(new PendingClarification(id, String.valueOf(row.get("original_question")),
                projects(row.get("candidate_projects"))));
    }

    public void complete(long id, boolean consumed) {
        jdbc.update("update agent_project_scope_clarification set status=? where id=? and status='CLAIMED'",
                consumed ? "CONSUMED" : "CANCELLED", id);
    }

    public Optional<SavedResolution> loadResolution(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select resolved_question, resolved_project_ids, project_scope_notice
                from agent_task where request_id=? and resolved_question is not null
                """, requestId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        return Optional.of(new SavedResolution(String.valueOf(row.get("resolved_question")),
                stringList(row.get("resolved_project_ids")),
                row.get("project_scope_notice") == null ? null : String.valueOf(row.get("project_scope_notice"))));
    }

    public void saveResolution(String requestId, String question, List<String> projectIds, String notice) {
        jdbc.update("""
                update agent_task set resolved_question=?, resolved_project_ids=cast(? as json),
                  project_scope_notice=? where request_id=?
                """, question, json(projectIds), notice, requestId);
    }

    private List<ProjectScopeService.ProjectOption> projects(Object value) {
        try { return mapper.readValue(jsonColumnText(value), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("待确认项目数据损坏", e); }
    }

    private List<String> stringList(Object value) {
        if (value == null) return List.of();
        try { return mapper.readValue(jsonColumnText(value), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("项目范围数据损坏", e); }
    }

    private String jsonColumnText(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
        String text = value instanceof byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(value);
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(text);
        return node.isTextual() ? node.textValue() : text;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法保存待确认项目", e); }
    }

    private String scopeKey(String openId, String chatId) { return openId + "\n" + safe(chatId); }
    private String safe(String value) { return value == null ? "" : value; }

    public record PendingClarification(long id, String originalQuestion,
                                       List<ProjectScopeService.ProjectOption> candidates) {}
    public record SavedResolution(String question, List<String> projectIds, String notice) {}
}
