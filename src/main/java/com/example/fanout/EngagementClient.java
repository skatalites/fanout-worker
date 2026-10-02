package com.example.fanout;

import java.time.Duration;

/**
 * Port to the slow downstream (owned by another team): ~1 minute per engagement, limited capacity.
 * <p>
 * Contract we ask of the other team: calls are idempotent on {@code idempotencyKey}, because we can
 * time out locally while their work still completes, and we will retry.
 */
public interface EngagementClient {

    /**
     * Resolves one engagement: loads it and refreshes its index row. Blocks for roughly a minute.
     *
     * @param idempotencyKey {@link Task#key()}; the implementation must de-duplicate on this across retries
     * @throws DownstreamException on any failure the worker should interpret (see the subclasses below)
     * @throws InterruptedException if the caller is shutting down; the call may still complete on the other side
     */
    void apply(Task task, String idempotencyKey) throws DownstreamException, InterruptedException;

    // Http Client Error Exception 400, 401, 403, 404
    class DownstreamException extends Exception {
        public DownstreamException(String message, Throwable cause) { super(message, cause); }
    }

    /** Retrying cannot help (e.g. engagement deleted). Goes straight to the DLQ. */
    final class PermanentException extends DownstreamException {
        public PermanentException(String message) { super(message, null); }
    }

    /** Retrying may help (timeout, 5xx,502,503,504, pod restarted). */
    class TransientException extends DownstreamException {
        public TransientException(String message, Throwable cause) { super(message, cause); }
    }

    /** Downstream says "too much" (HTTP 429-like). We pause ALL dispatching, not just this task. */
    final class ThrottledException extends TransientException {
        private final Duration retryAfter;
        public ThrottledException(Duration retryAfter) {
            super("throttled, retry after " + retryAfter, null);
            this.retryAfter = retryAfter;
        }
        public Duration retryAfter() { return retryAfter; }
    }
}
