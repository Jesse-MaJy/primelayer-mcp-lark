package com.larkconnect.agent.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRepositoryTraceMetricsTest {
    @Test
    void listsQueueProcessingMcpReturnedCountAndTokenMetricsFromTraceEvents() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:admin_trace_metrics;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        createSchema(jdbc);
        jdbc.update("""
                insert into agent_task(id, request_id, feishu_message_id, feishu_open_id, feishu_chat_id, chat_type,
                  message_text, status, created_at, started_at, finished_at)
                values (1, 'r1', 'm1', 'u1', 'c1', 'p2p', '问题', 'SUCCEEDED',
                  timestamp '2026-07-13 10:00:00', timestamp '2026-07-13 10:00:02', timestamp '2026-07-13 10:00:07')
                """);
        jdbc.update("insert into agent_audit_log(request_id, latency_ms) values ('r1', 4500)");
        jdbc.update("""
                insert into agent_trace_event(request_id, event_type, purpose, returned_count, input_tokens, output_tokens)
                values ('r1','model_call','decision',null,10,4),
                       ('r1','mcp_call','tool_discovery',1,0,0),
                       ('r1','mcp_call','tool_call',23,0,0),
                       ('r1','model_call','presentation',null,3,2)
                """);

        Map<String, Object> row = new AdminRepository(jdbc).listFeishuMessages().get(0);

        assertThat(((Number) row.get("processing_latency_ms")).longValue()).isEqualTo(4500);
        assertThat(((Number) row.get("queue_latency_ms")).longValue()).isEqualTo(2000);
        assertThat(((Number) row.get("model_call_count")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("business_mcp_call_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("returned_count")).intValue()).isEqualTo(23);
        assertThat(((Number) row.get("input_tokens")).intValue()).isEqualTo(13);
        assertThat(((Number) row.get("output_tokens")).intValue()).isEqualTo(6);
        assertThat(((Number) row.get("total_tokens")).intValue()).isEqualTo(19);
        assertThat(row.get("trace_completeness")).isEqualTo("COMPLETE");
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("create table agent_task(id bigint, request_id varchar, feishu_message_id varchar, feishu_open_id varchar, feishu_chat_id varchar, chat_type varchar, message_text varchar, status varchar, error_message varchar, created_at timestamp, started_at timestamp, finished_at timestamp)");
        jdbc.execute("create table agent_audit_log(request_id varchar, intent varchar, primelayer_user_id varchar, project_ids varchar, final_answer varchar, latency_ms bigint, error_message varchar, trace_status varchar, trace_error_message varchar)");
        jdbc.execute("create table user_binding(feishu_open_id varchar, person_name varchar)");
        jdbc.execute("create table agent_model_call_log(request_id varchar)");
        jdbc.execute("create table agent_tool_call_log(request_id varchar, tool_status varchar, tool_name varchar)");
        jdbc.execute("create table agent_answer_feedback(request_id varchar, rating varchar)");
        jdbc.execute("create table agent_chain_trace(request_id varchar)");
        jdbc.execute("create table agent_trace_event(request_id varchar, event_type varchar, purpose varchar, returned_count int, input_tokens int, output_tokens int)");
    }
}
