package com.omernaci.miniflow.workflow;

/**
 * Optional retry settings of a node: after a failure the node is executed again up to {@code times} times,
 * waiting {@code delayMs} before the first retry and multiplying the delay by {@code backoff} each time.
 */
public record RetryPolicy(int times, Long delayMs, Double backoff) {

    public static final int MAX_TIMES = 5;
    public static final long MAX_DELAY_MS = 30_000;
    public static final double MAX_BACKOFF = 10.0;

    public RetryPolicy {
        delayMs = delayMs == null ? 1000L : delayMs;
        backoff = backoff == null ? 2.0 : backoff;
    }

    public boolean isValid() {
        return times >= 0 && times <= MAX_TIMES
                && delayMs >= 0 && delayMs <= MAX_DELAY_MS
                && backoff >= 1.0 && backoff <= MAX_BACKOFF;
    }

    /** Delay before the given retry (1-based), capped at {@link #MAX_DELAY_MS}. */
    public long delayBeforeRetry(int retry) {
        double delay = delayMs * Math.pow(backoff, retry - 1);
        return (long) Math.min(delay, MAX_DELAY_MS);
    }
}
