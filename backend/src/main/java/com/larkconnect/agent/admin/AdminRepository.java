package com.larkconnect.agent.admin;

import com.larkconnect.agent.common.Status;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureBootstrapUser(String username, String passwordHash) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from admin_user where username = ?", Integer.class, username);
        if (count != null && count == 0) {
            jdbcTemplate.update("insert into admin_user(username, password_hash, status) values (?, ?, ?)", username, passwordHash, Status.ACTIVE);
        }
    }

    public AdminUser findAdminByUsername(String username) {
        try {
            return jdbcTemplate.queryForObject(
                    "select id, username, password_hash, status from admin_user where username = ?",
                    (rs, rowNum) -> new AdminUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("status")),
                    username
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<Map<String, Object>> listUserBindings() {
        return jdbcTemplate.queryForList("select id, person_name, feishu_open_id, status, created_at, updated_at from user_binding order by id desc");
    }

    public void upsertUserBinding(AdminDtos.UserBindingRequest request) {
        jdbcTemplate.update("""
                insert into user_binding(person_name, feishu_open_id, status)
                values (?, ?, ?)
                on duplicate key update person_name = values(person_name),
                  status = values(status)
                """, request.personName(), request.feishuOpenId(), request.status());
    }

    public List<Map<String, Object>> listProjectTokens() {
        return jdbcTemplate.queryForList("""
                select id, feishu_open_id, mcp_user_id, project_id, project_name, project_remark,
                  token_hash_suffix, token_status,
                  imported_by, imported_at, last_used_at, verify_status, last_verified_at, verify_error
                from project_mcp_token order by id desc
                """);
    }

    public Optional<ProjectTokenRecord> findProjectToken(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(jdbcTemplate.queryForObject("""
                    select id, feishu_open_id, mcp_user_id, project_id, project_name, project_remark,
                      mcp_token_ciphertext, token_hash_suffix, token_status, verify_status, verify_error
                    from project_mcp_token
                    where id = ?
                    """, this::mapProjectToken, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ProjectTokenRecord> findProjectToken(String feishuOpenId, String projectId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject("""
                    select id, feishu_open_id, mcp_user_id, project_id, project_name, project_remark,
                      mcp_token_ciphertext, token_hash_suffix, token_status, verify_status, verify_error
                    from project_mcp_token
                    where feishu_open_id = ? and lower(trim(project_id)) = lower(trim(?))
                    order by id desc
                    limit 1
                    """, this::mapProjectToken, feishuOpenId, projectId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsertProjectToken(
            AdminDtos.ProjectTokenRequest request,
            String mcpUserId,
            String ciphertext,
            String suffix,
            String importedBy,
            String verifyStatus,
            String verifyError
    ) {
        Optional<ProjectTokenRecord> existing = findProjectToken(request.id())
                .or(() -> findProjectToken(request.feishuOpenId(), request.projectId()));
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    update project_mcp_token
                    set feishu_open_id = ?, mcp_user_id = ?, project_id = ?, project_name = ?,
                      project_remark = ?, mcp_token_ciphertext = ?, token_hash_suffix = ?, token_status = ?,
                      imported_by = ?, imported_at = current_timestamp, verify_status = ?,
                      last_verified_at = current_timestamp, verify_error = ?
                    where id = ?
                    """, request.feishuOpenId(), mcpUserId, request.projectId(),
                    request.projectName(), request.projectRemark(), ciphertext, suffix, request.tokenStatus(),
                    importedBy, verifyStatus, verifyError, existing.get().id());
            return;
        }
        jdbcTemplate.update("""
                insert into project_mcp_token(feishu_open_id, mcp_user_id, project_id, project_name, project_remark, mcp_token_ciphertext, token_hash_suffix, token_status, imported_by, verify_status, last_verified_at, verify_error)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)
                """, request.feishuOpenId(), mcpUserId, request.projectId(), request.projectName(), request.projectRemark(), ciphertext, suffix, request.tokenStatus(), importedBy, verifyStatus, verifyError);
    }

    public void updateProjectTokenMetadata(AdminDtos.ProjectTokenRequest request) {
        ProjectTokenRecord existing = findProjectToken(request.id())
                .or(() -> findProjectToken(request.feishuOpenId(), request.projectId()))
                .orElseThrow(() -> new IllegalArgumentException("未找到可编辑的 MCP Token 配置，请先替换 Token。"));
        jdbcTemplate.update("""
                update project_mcp_token
                set feishu_open_id = ?, project_id = ?, project_name = ?,
                  project_remark = ?, token_status = ?
                where id = ?
                """, request.feishuOpenId(), request.projectId(), request.projectName(), request.projectRemark(),
                request.tokenStatus(), existing.id());
    }

    public void updateProjectTokenVerification(Long tokenId, String verifyStatus, String verifyError) {
        jdbcTemplate.update("""
                update project_mcp_token
                set verify_status = ?, last_verified_at = current_timestamp, verify_error = ?
                where id = ?
                """, verifyStatus, verifyError, tokenId);
    }


    public List<Map<String, Object>> listAuditLogs() {
        return jdbcTemplate.queryForList("select * from agent_audit_log order by id desc limit 200");
    }

    public List<Map<String, Object>> listAgentTasks() {
        return jdbcTemplate.queryForList("select * from agent_task order by id desc limit 200");
    }

    public List<Map<String, Object>> listFeishuMessages() {
        return jdbcTemplate.queryForList("""
                select
                  t.id,
                  t.request_id,
                  t.feishu_message_id,
                  t.feishu_open_id,
                  ub.person_name,
                  t.feishu_chat_id,
                  t.chat_type,
                  t.message_text,
                  t.status,
                  t.phase,
                  t.error_message as task_error,
                  t.created_at,
                  t.started_at,
                  t.finished_at,
                  a.intent,
                  a.primelayer_user_id,
                  a.project_ids,
                  a.final_answer,
                  a.latency_ms,
                  a.latency_ms as processing_latency_ms,
                  case when t.started_at is null then null
                       else timestampdiff(microsecond, t.created_at, t.started_at) / 1000 end as queue_latency_ms,
                  a.error_message as audit_error,
                  coalesce(ev.model_call_count, mc.model_call_count, 0) as model_call_count,
                  coalesce(tc.tool_call_count, 0) as tool_call_count,
                  coalesce(ev.business_mcp_call_count, tc.tool_call_count, 0) as business_mcp_call_count,
                  coalesce(ev.tool_discovery_count, 0) as tool_discovery_count,
                  coalesce(ev.returned_count, 0) as returned_count,
                  coalesce(ev.input_tokens, 0) as input_tokens,
                  coalesce(ev.output_tokens, 0) as output_tokens,
                  coalesce(ev.input_tokens, 0) + coalesce(ev.output_tokens, 0) as total_tokens,
                  case when a.trace_status = 'PARTIAL' then 'PARTIAL'
                       when coalesce(ev.event_count, 0) > 0 then 'COMPLETE'
                       when ct.request_id is not null then 'SUMMARY_ONLY'
                       else 'MISSING' end as trace_completeness,
                  coalesce(tc.failed_tool_call_count, 0) as failed_tool_call_count,
                  tn.tool_names,
                  coalesce(fb.helpful_count, 0) as helpful_count,
                  coalesce(fb.problem_count, 0) as problem_count,
                  coalesce(fb.feedback_count, 0) as feedback_count
                from agent_task t
                left join agent_audit_log a on a.request_id = t.request_id
                left join user_binding ub on ub.feishu_open_id = t.feishu_open_id
                left join agent_chain_trace ct on ct.request_id = t.request_id
                left join (
                  select request_id, count(*) as model_call_count
                  from agent_model_call_log
                  group by request_id
                ) mc on mc.request_id = t.request_id
                left join (
                  select request_id,
                         count(*) as tool_call_count,
                         sum(case when tool_status <> 'SUCCEEDED' then 1 else 0 end) as failed_tool_call_count
                  from agent_tool_call_log
                  group by request_id
                ) tc on tc.request_id = t.request_id
                left join (
                  select request_id,
                         count(*) as event_count,
                         sum(case when event_type = 'model_call' then 1 else 0 end) as model_call_count,
                         sum(case when event_type = 'mcp_call' and purpose = 'tool_call' then 1 else 0 end) as business_mcp_call_count,
                         sum(case when event_type = 'mcp_call' and purpose = 'tool_discovery' then 1 else 0 end) as tool_discovery_count,
                         sum(case when event_type = 'mcp_call' and purpose = 'tool_call' then coalesce(returned_count, 0) else 0 end) as returned_count,
                         sum(case when event_type = 'model_call' then coalesce(input_tokens, 0) else 0 end) as input_tokens,
                         sum(case when event_type = 'model_call' then coalesce(output_tokens, 0) else 0 end) as output_tokens
                  from agent_trace_event
                  group by request_id
                ) ev on ev.request_id = t.request_id
                left join (
                  select request_id,
                         group_concat(distinct tool_name order by tool_name separator ',') as tool_names
                  from agent_tool_call_log
                  group by request_id
                ) tn on tn.request_id = t.request_id
                left join (
                  select request_id,
                         sum(case when rating = 'HELPFUL' then 1 else 0 end) as helpful_count,
                         sum(case when rating = 'PROBLEM' then 1 else 0 end) as problem_count,
                         count(*) as feedback_count
                  from agent_answer_feedback
                  group by request_id
                ) fb on fb.request_id = t.request_id
                order by t.id desc
                limit 300
                """);
    }

    public record AdminUser(Long id, String username, String passwordHash, String status) {}

    public record ProjectTokenRecord(
            Long id,
            String feishuOpenId,
            String mcpUserId,
            String projectId,
            String projectName,
            String projectRemark,
            String mcpTokenCiphertext,
            String tokenHashSuffix,
            String tokenStatus,
            String verifyStatus,
            String verifyError
    ) {}

    private ProjectTokenRecord mapProjectToken(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProjectTokenRecord(
                rs.getLong("id"),
                rs.getString("feishu_open_id"),
                rs.getString("mcp_user_id"),
                rs.getString("project_id"),
                rs.getString("project_name"),
                rs.getString("project_remark"),
                rs.getString("mcp_token_ciphertext"),
                rs.getString("token_hash_suffix"),
                rs.getString("token_status"),
                rs.getString("verify_status"),
                rs.getString("verify_error")
        );
    }
}
