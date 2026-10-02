package com.example.fanout;

import java.time.Duration;

/**
 * @param maxAttempts        failures tolerated per task (first try included) before it goes to the DLQ
 * @param maxThrottleRetries throttle responses tolerated per task; they do NOT consume maxAttempts, but are bounded
 * @param callTimeout        local timeout for one downstream call (a typical call is ~1 min)
 * @param leaseMargin        extra time on top of callTimeout before another worker may take over a task
 * @param timeoutQuarantine  after a timeout the downstream may still be working: keep holding the capacity permit
 *                           for this long (about one typical call) so real concurrency does not exceed the budget
 */
public record FanOutConfig(int maxAttempts, int maxThrottleRetries, Duration callTimeout,
                           Duration baseBackoff, Duration maxBackoff, Duration leaseMargin,
                           Duration timeoutQuarantine) {

    public FanOutConfig {
        if (maxAttempts < 1 || maxThrottleRetries < 0) throw new IllegalArgumentException("invalid retry limits");
        if (callTimeout.isNegative() || callTimeout.isZero()) throw new IllegalArgumentException("callTimeout must be > 0");
    }

    /** Reasonable defaults: 5 attempts, up to 100 throttle retries, a 3-minute call timeout, 1-minute quarantine. */
    public static FanOutConfig defaults() {
        return new FanOutConfig(5, 100, Duration.ofMinutes(3),
                Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMinutes(1));
    }

    /** A claim is valid for the longest a single attempt can legitimately take. */
    public Duration lease() { return callTimeout.plus(leaseMargin); }
}
