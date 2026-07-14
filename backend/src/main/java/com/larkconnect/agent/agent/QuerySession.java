package com.larkconnect.agent.agent;

import java.time.Instant;

public record QuerySession(String requestId, QueryPhase phase, long version, String stateJson,
                           Instant nextRunAt, Instant heartbeatAt, Instant noticeAt,
                           Instant deadlineAt, boolean recoveredAfterRestart) {}
