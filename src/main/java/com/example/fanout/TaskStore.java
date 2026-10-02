package com.example.fanout;

import java.time.Instant;

/**
 * Durable per-task state: the source of truth for idempotency, leases and crash recovery.
 * Production mapping: one DynamoDB item per (jobId, engagementId); every method is a single conditional write
 * (the in-memory implementation uses ConcurrentHashMap.compute for the same atomicity).
 *
 * "failures" counts real failures only. A throttle response says nothing about the task, so it must not consume
 * the retry budget; an expired lease (worker crashed mid-call) does count, which protects against poison tasks.
 */
public interface TaskStore {

    enum State { PENDING, IN_PROGRESS, DONE, DEAD, CANCELLED }

    enum Outcome { CLAIMED, HELD_BY_OTHER, ALREADY_TERMINAL }

    record Claim(Outcome outcome, int failures, State state) {}

    /**
     * Atomically take the task if it is free, or if a previous owner's lease has expired.
     *
     * @param key        {@link Task#key()} of the task to claim
     * @param owner      id of the worker attempting the claim
     * @param now        current time, used to check whether an existing lease has expired
     * @param leaseUntil when this claim's own lease expires if granted
     * @return {@link Outcome#CLAIMED} with the task's failure count on success; {@link Outcome#HELD_BY_OTHER} if
     *         another worker's lease is still valid; {@link Outcome#ALREADY_TERMINAL} if the task is done/dead/cancelled
     */
    Claim tryClaim(String key, String owner, Instant now, Instant leaseUntil);

    /* The transitions below only succeed for the CURRENT owner (fencing against zombie workers). */

    /** Marks the task DONE. @return false if {@code owner} no longer holds the lease (another worker took over). */
    boolean markDone(String key, String owner);

    /**
     * Returns the task to PENDING so it can be retried.
     *
     * @param countsAsFailure whether this attempt should count toward {@link FanOutConfig#maxAttempts()}
     *                        (a throttle does not; a real failure does)
     * @return false if {@code owner} no longer holds the lease (another worker took over)
     */
    boolean releaseForRetry(String key, String owner, boolean countsAsFailure);

    /** Marks the task DEAD (sent to the {@link DeadLetterSink}). @return false if {@code owner} lost the lease. */
    boolean markDead(String key, String owner);

    /** Marks the task CANCELLED (its job is no longer active). @return false if {@code owner} lost the lease. */
    boolean markCancelled(String key, String owner);
}
