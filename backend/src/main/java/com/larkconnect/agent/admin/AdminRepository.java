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
        return jdbcTemplate.queryForList("select id, person_name, feishu_open_id, primelayer_user_id, primelayer_user_name, status, created_at, updated_at from user_binding order by id desc");
    }

    public void upsertUserBinding(AdminDtos.UserBindingRequest request) {
        jdbcTemplate.update("""
                insert into user_binding(person_name, feishu_open_id, primelayer_user_id, primelayer_user_name, status)
                values (?, ?, ?, ?, ?)
                on duplicate key update person_name = values(person_name),
                  primelayer_user_id = values(primelayer_user_id),
                  primelayer_user_name = values(primelayer_user_name), status = values(status)
                """, request.personName(), request.feishuOpenId(), request.primelayerUserId(), request.primelayerUserName(), request.status());
    }

    public List<Map<String, Object>> listProjectTokens() {
        return jdbcTemplate.queryForList("""
                select id, owner_type, owner_id, primelayer_user_id, project_id, project_name, project_remark,
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
                    select id, owner_type, owner_id, primelayer_user_id, project_id, project_name, project_remark,
                      mcp_token_ciphertext, token_hash_suffix, token_status, verify_status, verify_error
                    from project_mcp_token
                    where id = ?
                    """, this::mapProjectToken, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ProjectTokenRecord> findProjectToken(String ownerType, String ownerId, String projectId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject("""
                    select id, owner_type, owner_id, primelayer_user_id, project_id, project_name, project_remark,
                      mcp_token_ciphertext, token_hash_suffix, token_status, verify_status, verify_error
                    from project_mcp_token
                    where owner_type = ? and owner_id = ? and lower(trim(project_id)) = lower(trim(?))
                    order by id desc
                    limit 1
                    """, this::mapProjectToken, ownerType, ownerId, projectId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsertProjectToken(
            AdminDtos.ProjectTokenRequest request,
            String ciphertext,
            String suffix,
            String importedBy,
            String verifyStatus,
            String verifyError
    ) {
        Optional<ProjectTokenRecord> existing = findProjectToken(request.id())
                .or(() -> findProjectToken(request.ownerType(), request.ownerId(), request.projectId()));
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    update project_mcp_token
                    set owner_type = ?, owner_id = ?, primelayer_user_id = ?, project_id = ?, project_name = ?,
                      project_remark = ?, mcp_token_ciphertext = ?, token_hash_suffix = ?, token_status = ?,
                      imported_by = ?, imported_at = current_timestamp, verify_status = ?,
                      last_verified_at = current_timestamp, verify_error = ?
                    where id = ?
                    """, request.ownerType(), request.ownerId(), request.primelayerUserId(), request.projectId(),
                    request.projectName(), request.projectRemark(), ciphertext, suffix, request.tokenStatus(),
                    importedBy, verifyStatus, verifyError, existing.get().id());
            return;
        }
        jdbcTemplate.update("""
                insert into project_mcp_token(owner_type, owner_id, primelayer_user_id, project_id, project_name, project_remark, mcp_token_ciphertext, token_hash_suffix, token_status, imported_by, verify_status, last_verified_at, verify_error)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, ?)
                """, request.ownerType(), request.ownerId(), request.primelayerUserId(), request.projectId(), request.projectName(), request.projectRemark(), ciphertext, suffix, request.tokenStatus(), importedBy, verifyStatus, verifyError);
    }

    public void updateProjectTokenMetadata(AdminDtos.ProjectTokenRequest request) {
        ProjectTokenRecord existing = findProjectToken(request.id())
                .or(() -> findProjectToken(request.ownerType(), request.ownerId(), request.projectId()))
                .orElseThrow(() -> new IllegalArgumentException("未找到可编辑的 MCP Token 配置，请先替换 Token。"));
        jdbcTemplate.update("""
                update project_mcp_token
                set owner_type = ?, owner_id = ?, primelayer_user_id = ?, project_id = ?, project_name = ?,
                  project_remark = ?, token_status = ?
                where id = ?
                """, request.ownerType(), request.ownerId(), request.primelayerUserId(), request.projectId(),
                request.projectName(), request.projectRemark(), request.tokenStatus(), existing.id());
    }

    public void updateProjectTokenVerification(Long tokenId, String verifyStatus, String verifyError) {
        jdbcTemplate.update("""
                update project_mcp_token
                set verify_status = ?, last_verified_at = current_timestamp, verify_error = ?
                where id = ?
                """, verifyStatus, verifyError, tokenId);
    }

    public List<Map<String, Object>> listChatBindings() {
        return jdbcTemplate.queryForList("select id, feishu_chat_id, project_id, project_name, status, created_by, created_at, updated_at from feishu_chat_project_binding order by id desc");
    }

    public void upsertChatBinding(AdminDtos.ChatProjectBindingRequest request, String createdBy) {
        jdbcTemplate.update("""
                insert into feishu_chat_project_binding(feishu_chat_id, project_id, project_name, status, created_by)
                values (?, ?, ?, ?, ?)
                on duplicate key update project_id = values(project_id), project_name = values(project_name), status = values(status)
                """, request.feishuChatId(), request.projectId(), request.projectName(), request.status(), createdBy);
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
                  t.error_message as task_error,
                  t.created_at,
                  t.started_at,
                  t.finished_at,
                  a.intent,
                  a.primelayer_user_id,
                  a.project_ids,
                  a.final_answer,
                  a.latency_ms,
                  a.error_message as audit_error,
                  coalesce(mc.model_call_count, 0) as model_call_count,
                  coalesce(tc.tool_call_count, 0) as tool_call_count,
                  coalesce(tc.failed_tool_call_count, 0) as failed_tool_call_count,
                  tn.tool_names
                from agent_task t
                left join agent_audit_log a on a.request_id = t.request_id
                left join user_binding ub on ub.feishu_open_id = t.feishu_open_id
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
                         group_concat(distinct tool_name order by tool_name separator ',') as tool_names
                  from agent_tool_call_log
                  group by request_id
                ) tn on tn.request_id = t.request_id
                order by t.id desc
                limit 300
                """);
    }

    public record AdminUser(Long id, String username, String passwordHash, String status) {}

    public record ProjectTokenRecord(
            Long id,
            String ownerType,
            String ownerId,
            String primelayerUserId,
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
                rs.getString("owner_type"),
                rs.getString("owner_id"),
                rs.getString("primelayer_user_id"),
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
