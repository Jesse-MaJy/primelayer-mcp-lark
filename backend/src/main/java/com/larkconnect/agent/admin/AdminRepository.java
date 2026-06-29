package com.larkconnect.agent.admin;

import com.larkconnect.agent.common.Status;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

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
        return jdbcTemplate.queryForList("select id, feishu_open_id, primelayer_user_id, primelayer_user_name, status, created_at, updated_at from user_binding order by id desc");
    }

    public void upsertUserBinding(AdminDtos.UserBindingRequest request) {
        jdbcTemplate.update("""
                insert into user_binding(feishu_open_id, primelayer_user_id, primelayer_user_name, status)
                values (?, ?, ?, ?)
                on duplicate key update primelayer_user_id = values(primelayer_user_id),
                  primelayer_user_name = values(primelayer_user_name), status = values(status)
                """, request.feishuOpenId(), request.primelayerUserId(), request.primelayerUserName(), request.status());
    }

    public List<Map<String, Object>> listProjectTokens() {
        return jdbcTemplate.queryForList("select id, primelayer_user_id, project_id, project_name, token_hash_suffix, token_status, imported_by, imported_at, last_used_at from project_mcp_token order by id desc");
    }

    public void upsertProjectToken(AdminDtos.ProjectTokenRequest request, String ciphertext, String suffix, String importedBy) {
        jdbcTemplate.update("""
                insert into project_mcp_token(primelayer_user_id, project_id, project_name, mcp_token_ciphertext, token_hash_suffix, token_status, imported_by)
                values (?, ?, ?, ?, ?, ?, ?)
                on duplicate key update project_name = values(project_name),
                  mcp_token_ciphertext = values(mcp_token_ciphertext), token_hash_suffix = values(token_hash_suffix),
                  token_status = values(token_status), imported_by = values(imported_by), imported_at = current_timestamp
                """, request.primelayerUserId(), request.projectId(), request.projectName(), ciphertext, suffix, request.tokenStatus(), importedBy);
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

    public record AdminUser(Long id, String username, String passwordHash, String status) {}
}
