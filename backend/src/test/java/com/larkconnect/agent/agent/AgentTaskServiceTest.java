package com.larkconnect.agent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentTaskServiceTest {
    private JdbcTemplate jdbc;
    private AgentTaskService service;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:agent-task;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop table if exists agent_task");
        jdbc.execute("""
                create table agent_task(request_id varchar(128) primary key, status varchar(32), phase varchar(32),
                  error_message clob, started_at timestamp, heartbeat_at timestamp, finished_at timestamp)
                """);
        service = new AgentTaskService(jdbc, mock(RabbitTemplate.class));
    }

    @Test
    void workerStateTransitionsNeverOverwriteCancelledTask() {
        jdbc.update("insert into agent_task(request_id,status,phase) values ('r1','CANCELLED','CANCELLED')");

        assertThat(service.markRunning("r1")).isFalse();
        service.markSucceeded("r1");
        service.markPartial("r1", "partial");
        service.markFailed("r1", "failed");

        assertThat(jdbc.queryForMap("select status, phase, error_message from agent_task where request_id='r1'"))
                .containsEntry("STATUS", "CANCELLED")
                .containsEntry("PHASE", "CANCELLED")
                .containsEntry("ERROR_MESSAGE", null);
    }
}
