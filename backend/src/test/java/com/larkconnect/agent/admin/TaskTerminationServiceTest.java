package com.larkconnect.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.agent.DeliveryOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTerminationServiceTest {
    private JdbcTemplate jdbc;
    private TaskTerminationService service;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:termination;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop table if exists agent_delivery_outbox");
        jdbc.execute("drop table if exists agent_query_checkpoint");
        jdbc.execute("drop table if exists agent_task");
        jdbc.execute("""
                create table agent_task(request_id varchar(128) primary key, status varchar(32), phase varchar(32),
                  feishu_message_id varchar(128), message_text varchar(255), error_message clob,
                  finished_at timestamp)
                """);
        jdbc.execute("""
                create table agent_query_checkpoint(request_id varchar(128) primary key, phase varchar(32),
                  version bigint, next_run_at timestamp, heartbeat_at timestamp, updated_at timestamp)
                """);
        jdbc.execute("""
                create table agent_delivery_outbox(id bigint auto_increment primary key, request_id varchar(128),
                  delivery_type varchar(32), payload_json clob, status varchar(32), attempts int,
                  next_attempt_at timestamp, lease_until timestamp, sent_at timestamp,
                  feishu_message_id varchar(128), last_error clob, unique(request_id, delivery_type))
                """);
        service = new TaskTerminationService(jdbc, new DeliveryOutboxRepository(jdbc), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void cancelsActiveTaskCheckpointAndQueuesOneNotice() {
        jdbc.update("insert into agent_task(request_id,status,phase,feishu_message_id,message_text) values (?,?,?,?,?)",
                "r1", "RUNNING", "COLLECTING", "m1", "查询项目");
        jdbc.update("insert into agent_query_checkpoint values (?,?,?,?,?,?)",
                "r1", "COLLECTING", 2, java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));

        assertThat(service.terminate("r1").status()).isEqualTo("CANCELLED");
        assertThat(service.terminate("r1").status()).isEqualTo("CANCELLED");

        assertThat(jdbc.queryForObject("select status from agent_task where request_id='r1'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select phase from agent_query_checkpoint where request_id='r1'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from agent_delivery_outbox where delivery_type='TASK_CANCELLED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsWhenFinalResultIsAlreadySending() {
        jdbc.update("insert into agent_task(request_id,status,phase,feishu_message_id,message_text) values (?,?,?,?,?)",
                "r2", "RUNNING", "FINALIZING", "m2", "查询项目");
        jdbc.update("""
                insert into agent_delivery_outbox(request_id,delivery_type,payload_json,status,attempts,next_attempt_at)
                values ('r2','FINAL_RESULT','{}','SENDING',1,current_timestamp)
                """);

        assertThatThrownBy(() -> service.terminate("r2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正在发送");
        assertThat(jdbc.queryForObject("select status from agent_task where request_id='r2'", String.class))
                .isEqualTo("RUNNING");
    }

    @Test
    void cancelsPendingTaskBeforeWorkerStarts() {
        jdbc.update("insert into agent_task(request_id,status,phase,feishu_message_id,message_text) values (?,?,?,?,?)",
                "r-pending", "PENDING", "PLANNING", "m-pending", "查询项目");

        assertThat(service.terminate("r-pending").status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "select status from agent_task where request_id='r-pending'", String.class))
                .isEqualTo("CANCELLED");
    }

    @Test
    void rejectsTerminalTaskThatWasNotCancelled() {
        jdbc.update("insert into agent_task(request_id,status,phase,feishu_message_id,message_text) values (?,?,?,?,?)",
                "r-complete", "SUCCEEDED", "COMPLETED", "m-complete", "查询项目");

        assertThatThrownBy(() -> service.terminate("r-complete"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("待处理或运行中");
        assertThat(jdbc.queryForObject(
                "select status from agent_task where request_id='r-complete'", String.class))
                .isEqualTo("SUCCEEDED");
    }
}
