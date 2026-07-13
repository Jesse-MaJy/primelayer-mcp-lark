package com.larkconnect.agent.feishu;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerFeedbackRepositoryTest {
    private JdbcTemplate jdbc;
    private AnswerFeedbackRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:feedback;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("""
                create table agent_task(
                  request_id varchar(128) primary key,
                  feishu_message_id varchar(128),
                  message_text clob
                )
                """);
        jdbc.execute("""
                create table agent_audit_log(
                  request_id varchar(128) primary key,
                  final_answer clob,
                  intent varchar(128)
                )
                """);
        jdbc.execute("""
                create table user_binding(
                  feishu_open_id varchar(128) primary key,
                  person_name varchar(128)
                )
                """);
        jdbc.execute("""
                create table agent_answer_feedback(
                  id bigint auto_increment primary key,
                  request_id varchar(128) not null,
                  feishu_open_id varchar(128) not null,
                  rating varchar(32) not null,
                  reason_code varchar(32),
                  detail varchar(500),
                  feishu_message_id varchar(128),
                  last_event_id varchar(128) not null,
                  last_event_time bigint not null,
                  created_at timestamp default current_timestamp,
                  updated_at timestamp default current_timestamp,
                  unique(request_id, feishu_open_id)
                )
                """);
        repository = new AnswerFeedbackRepository(jdbc);
    }

    @Test
    void loadsOnlyNormalAiAnswers() {
        jdbc.update("insert into agent_task values (?, ?, ?)", "req-1", "om-1", "问题");
        jdbc.update("insert into agent_audit_log values (?, ?, ?)", "req-1", "回答", "mcp_deepseek");

        AnswerFeedbackRepository.AnswerContext context = repository.findAnswer("req-1").orElseThrow();

        assertEquals("项目数据分析", context.title());
        assertTrue(repository.findAnswer("missing").isEmpty());
    }

    @Test
    void upsertsLatestFeedbackAndIgnoresOlderEvent() {
        AnswerFeedbackRepository.SaveOutcome first = repository.upsert(write("HELPFUL", null, "evt-2", 200));
        AnswerFeedbackRepository.SaveOutcome stale = repository.upsert(write("PROBLEM", "INCOMPLETE", "evt-1", 100));
        AnswerFeedbackRepository.SaveOutcome changed = repository.upsert(write("PROBLEM", "INCOMPLETE", "evt-3", 300));

        assertTrue(first.applied());
        assertFalse(stale.applied());
        assertTrue(changed.applied());
        assertEquals("PROBLEM", repository.findFeedback("req-1", "ou-1").orElseThrow().rating());
        assertEquals(1, jdbc.queryForObject("select count(*) from agent_answer_feedback", Integer.class));
    }

    @Test
    void returnsFeedbackDetailsWithPersonNameAndReasonLabel() {
        jdbc.update("insert into user_binding values (?, ?)", "ou-1", "张三");
        repository.upsert(write("PROBLEM", "DATA_INACCURATE", "evt-1", 100));

        AnswerFeedbackRepository.FeedbackDetail detail = repository.listDetails("req-1").get(0);

        assertEquals("张三", detail.personName());
        assertEquals("数据不准确", detail.reasonLabel());
    }

    private AnswerFeedbackRepository.FeedbackWrite write(String rating, String reason, String eventId, long eventTime) {
        return new AnswerFeedbackRepository.FeedbackWrite(
                "req-1", "ou-1", rating, reason, null, "om-1", eventId, eventTime
        );
    }
}
