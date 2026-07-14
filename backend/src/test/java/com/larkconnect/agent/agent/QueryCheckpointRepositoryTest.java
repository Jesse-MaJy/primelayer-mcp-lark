package com.larkconnect.agent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCheckpointRepositoryTest {
    private QueryCheckpointRepository repository;

    @BeforeEach
    void setup() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:checkpoint;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop table if exists agent_query_checkpoint");
        jdbc.execute("create table agent_query_checkpoint(request_id varchar(128) primary key, phase varchar(32), version bigint, state_json clob, next_run_at timestamp, heartbeat_at timestamp, notice_at timestamp, deadline_at timestamp, recovered_after_restart boolean default false, created_at timestamp, updated_at timestamp)");
        repository = new QueryCheckpointRepository(jdbc);
    }

    @Test
    void compareAndSetPreventsTwoWorkersFromAdvancingSameSession() {
        Instant created = Instant.parse("2026-07-14T00:00:00Z");
        QuerySession session = repository.initialize("r1", created);

        assertThat(repository.advance("r1", session.version(), QueryPhase.FETCHING_PAGE,
                "{\"page\":1}", created.plusSeconds(5))).isTrue();
        assertThat(repository.advance("r1", session.version(), QueryPhase.FAILED,
                "{}", created.plusSeconds(5))).isFalse();
        assertThat(repository.load("r1").orElseThrow().phase()).isEqualTo(QueryPhase.FETCHING_PAGE);
    }

    @Test
    void findsDueAndStaleSessionsForRestartRecovery() {
        Instant created = Instant.parse("2026-07-14T00:00:00Z");
        QuerySession session = repository.initialize("r2", created);
        repository.advance("r2", session.version(), QueryPhase.POLLING_ASYNC, "{}", created.plusSeconds(5));

        assertThat(repository.findRecoverable(created.plusSeconds(70), created.plusSeconds(60)))
                .extracting(QuerySession::requestId).contains("r2");
    }

    @Test
    void leaseClaimRejectsDuplicateDeliveryUntilLeaseExpires() {
        Instant created = Instant.parse("2026-07-14T00:00:00Z");
        repository.initialize("r3", created);

        assertThat(repository.claim("r3", created, Duration.ofMinutes(1))).isTrue();
        assertThat(repository.claim("r3", created.plusSeconds(1), Duration.ofMinutes(1))).isFalse();
        assertThat(repository.claim("r3", created.plusSeconds(61), Duration.ofMinutes(1))).isTrue();
    }
}
