package com.larkconnect.agent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryOutboxRepositoryTest {
    private DeliveryOutboxRepository repository;

    @BeforeEach
    void setup() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:outbox;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop table if exists agent_delivery_outbox");
        jdbc.execute("drop table if exists agent_task");
        jdbc.execute("create table agent_task(request_id varchar(128) primary key, status varchar(32))");
        jdbc.execute("create table agent_delivery_outbox(id bigint auto_increment primary key, request_id varchar(128), delivery_type varchar(32), payload_json clob, status varchar(32), attempts int, next_attempt_at timestamp, lease_until timestamp, sent_at timestamp, feishu_message_id varchar(128), last_error clob, unique(request_id, delivery_type))");
        jdbc.update("insert into agent_task values (?,?)", "r1", "RUNNING");
        repository = new DeliveryOutboxRepository(jdbc);
    }

    @Test
    void enqueuesEachDeliveryTypeOnlyOnceAndUsesLeaseWhenClaiming() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        assertThat(repository.enqueueOnce("r1", DeliveryType.LONG_RUNNING_NOTICE, "{}", now)).isTrue();
        assertThat(repository.enqueueOnce("r1", DeliveryType.LONG_RUNNING_NOTICE, "{}", now)).isFalse();

        DeliveryOutboxEntry claimed = repository.claimDue(now, now.plusSeconds(30)).orElseThrow();
        assertThat(claimed.status()).isEqualTo("SENDING");
        assertThat(repository.claimDue(now, now.plusSeconds(30))).isEmpty();
        assertThat(repository.claimDue(now.plusSeconds(31), now.plusSeconds(61))).isPresent();
    }
}
