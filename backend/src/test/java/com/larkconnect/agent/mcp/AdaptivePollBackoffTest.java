package com.larkconnect.agent.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptivePollBackoffTest {
    @Test
    void increasesDelayAndHonorsRetryAfter() {
        AdaptivePollBackoff backoff = new AdaptivePollBackoff(0.0);
        assertThat(backoff.delay(0, Duration.ZERO, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delay(1, Duration.ofSeconds(2), null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delay(5, Duration.ofMinutes(2), null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.delay(20, Duration.ofMinutes(10), null)).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.delay(2, Duration.ofSeconds(5), Duration.ofSeconds(17)))
                .isEqualTo(Duration.ofSeconds(17));
    }
}
