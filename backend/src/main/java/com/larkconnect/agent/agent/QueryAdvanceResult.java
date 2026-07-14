package com.larkconnect.agent.agent;

import java.time.Instant;

public record QueryAdvanceResult(QueryPhase phase, boolean terminal, Instant nextRunAt, String stopReason) {
    public static QueryAdvanceResult waiting(QueryPhase phase, Instant nextRunAt) {
        return new QueryAdvanceResult(phase, false, nextRunAt, null);
    }
    public static QueryAdvanceResult terminal(QueryPhase phase, String reason) {
        return new QueryAdvanceResult(phase, true, null, reason);
    }
}
