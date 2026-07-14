package com.larkconnect.agent.agent;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class QueryRecoveryScheduler {
    private final QueryCheckpointRepository checkpoints;
    private final QueryResumePublisher publisher;
    private final Clock clock;

    public QueryRecoveryScheduler(QueryCheckpointRepository checkpoints, QueryResumePublisher publisher, Clock clock) {
        this.checkpoints = checkpoints;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverDueQueries() {
        Instant now = clock.instant();
        for (QuerySession session : checkpoints.findRecoverable(now, now.minus(Duration.ofMinutes(1)))) {
            checkpoints.markRecovered(session.requestId());
            publisher.schedule(session.requestId(), Duration.ZERO);
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeatRunningQueries() {
        checkpoints.heartbeatRunningTasks();
    }
}
