package com.larkconnect.agent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationHistoryServiceTest {
    private JdbcTemplate jdbc;
    private ConversationHistoryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:history;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists agent_task");
        jdbc.execute("drop table if exists agent_audit_log");
        jdbc.execute("create table agent_task (request_id varchar(40), feishu_open_id varchar(40), feishu_chat_id varchar(40), chat_type varchar(20), message_text text, status varchar(20), created_at timestamp)");
        jdbc.execute("create table agent_audit_log (request_id varchar(40), final_answer text, intent varchar(40))");
        service = new ConversationHistoryService(jdbc);
    }

    @Test
    void groupHistoryIsSharedAndLimitedToFiveCompletedBusinessTurns() {
        for (int i = 1; i <= 7; i++) insert("r" + i, "u" + i, "g1", "group", "q" + i, "SUCCEEDED", "a" + i, "mcp_deepseek");
        insert("control", "u1", "g1", "group", "配置正常", "SUCCEEDED", "ok", "mcp_config_status");
        insert("failed", "u1", "g1", "group", "bad", "FAILED", "no", "mcp_deepseek");
        insert("ai-failed", "u1", "g1", "group", "service down", "SUCCEEDED", "unavailable", "ai_unavailable");

        assertThat(service.load("group", "u1", "g1", "current"))
                .extracting(ConversationHistoryService.HistoryTurn::question)
                .containsExactly("q3", "q4", "q5", "q6", "q7");
    }

    @Test
    void privateHistoryIsolatedByOpenId() {
        insert("mine", "u1", "p2p-chat", "p2p", "mine", "SUCCEEDED", "a", "direct_deepseek");
        insert("other", "u2", "p2p-chat", "p2p", "other", "SUCCEEDED", "b", "direct_deepseek");

        assertThat(service.load("p2p", "u1", "p2p-chat", "current"))
                .extracting(ConversationHistoryService.HistoryTurn::question)
                .containsExactly("mine");
    }

    private void insert(String requestId, String openId, String chatId, String chatType, String question, String status, String answer, String intent) {
        jdbc.update("insert into agent_task values (?, ?, ?, ?, ?, ?, current_timestamp)", requestId, openId, chatId, chatType, question, status);
        jdbc.update("insert into agent_audit_log values (?, ?, ?)", requestId, answer, intent);
    }
}
