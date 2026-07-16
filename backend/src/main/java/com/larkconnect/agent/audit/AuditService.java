package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void writeMain(String requestId, String openId, String chatId, String primelayerUserId, List<String> projectIds, String question, String intent, String answer, long latencyMs, String error) {
        writeMain(requestId, openId, chatId, primelayerUserId, projectIds, question, intent, answer,
                null, latencyMs, error);
    }

    public void writeMain(String requestId, String openId, String chatId, String primelayerUserId,
                          List<String> projectIds, String question, String intent, String answer,
                          String presentationJson, long latencyMs, String error) {
        jdbcTemplate.update("""
                insert into agent_audit_log(request_id, feishu_open_id, feishu_chat_id, primelayer_user_id, project_ids, user_question, intent, final_answer, presentation_json, latency_ms, error_message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on duplicate key update final_answer = values(final_answer), presentation_json = values(presentation_json),
                  latency_ms = values(latency_ms), error_message = values(error_message)
                """, requestId, openId, chatId, primelayerUserId, toJson(projectIds), question, intent,
                answer, presentationJson, latencyMs, error);
    }

    public void writeTool(String requestId, String projectId, String primelayerUserId, String toolName, Map<String, Object> arguments, String status, long latencyMs, String error) {
        jdbcTemplate.update("""
                insert into agent_tool_call_log(request_id, project_id, primelayer_user_id, tool_name, tool_arguments, tool_status, latency_ms, error_message)
                values (?, ?, ?, ?, cast(? as json), ?, ?, ?)
                """, requestId, projectId, primelayerUserId, toolName, toJson(arguments), status, latencyMs, error);
    }

    public void writeModel(String requestId, String model, String purpose, String inputSummary, String outputText, String status, long latencyMs, String error) {
        jdbcTemplate.update("""
                insert into agent_model_call_log(request_id, model_name, purpose, input_summary, output_text, status, latency_ms, error_message)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, model, purpose, inputSummary, outputText, status, latencyMs, error);
    }

    public void writeTraceStatus(String requestId, String status, String error) {
        jdbcTemplate.update("update agent_audit_log set trace_status = ?, trace_error_message = ? where request_id = ?",
                status, error, requestId);
    }

    public void updateProcessingLatency(String requestId, long latencyMs) {
        jdbcTemplate.update("update agent_audit_log set latency_ms = ? where request_id = ?", latencyMs, requestId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }
}
