package com.larkconnect.agent.mcp;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class AdaptivePollBackoff {
    private final double jitterRatio;

    public AdaptivePollBackoff(double jitterRatio) {
        this.jitterRatio = Math.max(0, Math.min(0.5, jitterRatio));
    }

    public Duration delay(int attempt, Duration elapsed, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) return retryAfter;
        long seconds;
        if (elapsed.compareTo(Duration.ofMinutes(10)) >= 0) seconds = 30;
        else if (elapsed.compareTo(Duration.ofMinutes(5)) >= 0) seconds = 20;
        else if (elapsed.compareTo(Duration.ofMinutes(1)) >= 0) seconds = 10;
        else seconds = new long[]{1, 2, 4, 5}[Math.min(Math.max(attempt, 0), 3)];
        if (jitterRatio == 0) return Duration.ofSeconds(seconds);
        double factor = ThreadLocalRandom.current().nextDouble(1 - jitterRatio, 1 + jitterRatio);
        return Duration.ofMillis(Math.max(250, Math.round(seconds * 1000 * factor)));
    }
}
