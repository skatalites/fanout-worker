package com.example.fanout;

import com.example.fanout.EngagementClient.DownstreamException;
import com.example.fanout.EngagementClient.PermanentException;
import com.example.fanout.EngagementClient.ThrottledException;
import com.example.fanout.EngagementClient.TransientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Fan-out worker: one job -> N "resolve this engagement" tasks -> a slow, capacity-limited downstream call each.
 * Used for publish verification, backfill and reconciliation jobs alike.
 * <p>
 * Guarantees:
 *  - effectively-once per (jobId, engagementId): claim in the TaskStore before calling, skip anything terminal
 *  - downstream capacity is a shared CapacityBudget (all workers/jobs draw from one budget)
 *  - a timed-out call keeps holding its permit for a quarantine period, because the downstream may still be working
 *  - fairness: round-robin across firms
 *  - failures: transient -> retry with exponential backoff + jitter; permanent / exhausted -> DLQ
 *  - throttles pause all dispatching and do not consume the retry budget (bounded separately)
 *  - crash recovery: leases expire, so another run can take over; re-running a finished job is a no-op
 *  - withdrawn jobs stop being processed
 */
public final class FanOutWorker {

    private static final int PAGE_SIZE = 500;

    private final EngagementSource source;
    private final TaskStore store;
    private final EngagementClient client;
    private final DeadLetterSink dlq;
    private final JobGuard guard;
    private final CapacityBudget budget;
    private final FanOutConfig cfg;
    private final Clock clock;
    private final String workerId = "worker-" + UUID.randomUUID();

    /**
     * @param source the engagements of a job; paged through once per {@link #run}
     * @param store  durable per-task state (claims, leases, failure counts)
     * @param client the slow downstream call; must be idempotent on the key passed to it
     * @param dlq    where tasks go once they cannot succeed
     * @param guard  lets a withdrawn/superseded job stop early
     * @param budget the shared downstream capacity; pass the SAME instance to every worker/job
     * @param cfg    retry, timeout and lease tuning
     * @param clock  injected for deterministic tests
     */
    public FanOutWorker(EngagementSource source, TaskStore store, EngagementClient client, DeadLetterSink dlq,
                        JobGuard guard, CapacityBudget budget, FanOutConfig cfg, Clock clock) {
        this.source = source;
        this.store = store;
        this.client = client;
        this.dlq = dlq;
        this.guard = guard;
        this.budget = budget;
        this.cfg = cfg;
        this.clock = clock;
    }

    /** Per-run mutable state. */
    private static final class Run {

        final FairQueue queue = new FairQueue();
        final AtomicInteger outstanding = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger(), dead = new AtomicInteger(), cancelled = new AtomicInteger(),
                skippedTerminal = new AtomicInteger(), skippedHeld = new AtomicInteger(),
                lostLease = new AtomicInteger(), retries = new AtomicInteger(), throttled = new AtomicInteger();
        final ConcurrentHashMap<String, AtomicInteger> throttlesPerTask = new ConcurrentHashMap<>();
        int total;
        ScheduledExecutorService timer;
        ExecutorService exec;
    }

    /** Blocks until every task of the job reached a terminal state (or this run was interrupted). */
    public JobSummary run(String jobId) throws InterruptedException {
        Run r = new Run();
        r.total = load(jobId, r);
        r.outstanding.set(r.total);
        r.timer = Executors.newSingleThreadScheduledExecutor(rr -> {
            Thread t = new Thread(rr, "fanout-retry-timer");
            t.setDaemon(true);
            return t;
        });
        r.exec = Executors.newVirtualThreadPerTaskExecutor(); // blocking 1-minute calls are cheap on virtual threads
        try {
            while (r.outstanding.get() > 0) {
                budget.awaitUnpaused();
                Task t = r.queue.poll(50, MILLISECONDS);
                if (t == null) continue;      // nothing ready now (e.g. everything left is waiting for a retry)
                budget.acquire();             // the shared capacity contract with the downstream team
                try {
                    budget.awaitUnpaused();   // a throttle may have arrived while we waited for the permit
                } catch (InterruptedException e) {
                    budget.release();
                    throw e;
                }
                r.exec.submit(() -> {
                    boolean quarantine = false;
                    try {
                        quarantine = process(t, r);
                    } finally {
                        if (quarantine) budget.releaseAfter(cfg.timeoutQuarantine()); else budget.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            // Leave unfinished tasks as PENDING/IN_PROGRESS: their leases expire and a later run resumes them.
            r.exec.shutdownNow();
            throw e;
        } finally {
            r.timer.shutdownNow();
            r.exec.close();
        }
        return new JobSummary(r.total, r.done.get(), r.dead.get(), r.cancelled.get(),
                r.skippedTerminal.get(), r.skippedHeld.get(), r.lostLease.get(), r.retries.get(), r.throttled.get());
    }

    /** Pages through every engagement of {@code jobId}, de-duplicates, and fills the run's fair queue. @return task count. */
    private int load(String jobId, Run r) {
        Map<String, Task> unique = new LinkedHashMap<>(); // de-duplicate if the source repeats an engagement across pages
        String cursor = null;
        do {
            EngagementSource.Page page = source.next(jobId, cursor, PAGE_SIZE);
            for (Task t : page.tasks()) unique.putIfAbsent(t.engagementId(), t);
            cursor = page.nextCursor();
        } while (cursor != null);
        unique.values().forEach(r.queue::offer);
        return unique.size();
    }

    /** @return true if the capacity permit must stay held for the timeout quarantine. */
    private boolean process(Task t, Run r) {
        Instant now = clock.instant();
        TaskStore.Claim claim = store.tryClaim(t.key(), workerId, now, now.plus(cfg.lease()));
        switch (claim.outcome()) {
            case ALREADY_TERMINAL -> { r.skippedTerminal.incrementAndGet(); r.outstanding.decrementAndGet(); return false; }
            case HELD_BY_OTHER    -> { r.skippedHeld.incrementAndGet();     r.outstanding.decrementAndGet(); return false; }
            case CLAIMED          -> { /* proceed */ }
        }

        if (!guard.isActive(t.jobId())) {
            if (store.markCancelled(t.key(), workerId)) r.cancelled.incrementAndGet(); else r.lostLease.incrementAndGet();
            r.outstanding.decrementAndGet();
            return false;
        }

        try {
            callWithTimeout(t, r.exec);
            // If our lease was taken over meanwhile this returns false: the other worker will redo it, which is safe
            // because the downstream call is idempotent on the key.
            if (store.markDone(t.key(), workerId)) r.done.incrementAndGet(); else r.lostLease.incrementAndGet();
            r.outstanding.decrementAndGet();
            return false;
        } catch (ThrottledException e) {
            budget.pauseFor(e.retryAfter());
            int n = r.throttlesPerTask.computeIfAbsent(t.key(), k -> new AtomicInteger()).incrementAndGet();
            if (n > cfg.maxThrottleRetries()) {
                die(t, "throttled too many times (" + n + ")", e, r);
            } else {
                r.throttled.incrementAndGet();
                scheduleRetry(t, false, n, e.retryAfter(), r); // a throttle is not the task's fault: no failure counted
            }
            return false;
        } catch (PermanentException e) {
            die(t, "permanent failure", e, r);
            return false;
        } catch (TimeoutException e) {
            failOrRetry(t, claim, e, r);
            return true; // the downstream may still be working: keep the permit during the quarantine
        } catch (RuntimeException | DownstreamException e) {
            failOrRetry(t, claim, e, r);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down: leave the lease to expire, a later run resumes
            return false;
        }
    }

    /**
     * Runs the downstream call with a local timeout. On timeout the call is cancelled but may still be running on
     * the other side, which is why {@link EngagementClient#apply} must be idempotent.
     */
    private void callWithTimeout(Task t, ExecutorService exec)
            throws DownstreamException, TimeoutException, InterruptedException {
        Future<Void> f = exec.submit(() -> { client.apply(t, t.key()); return null; });
        try {
            f.get(cfg.callTimeout().toMillis(), MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof DownstreamException d) throw d;
            if (c instanceof RuntimeException re) throw re;
            throw new TransientException("unexpected failure", c);
        } catch (TimeoutException | InterruptedException e) {
            f.cancel(true); // the downstream may still finish; that is why the call is idempotent and the permit is quarantined
            throw e;
        }
    }

    /** Sends {@code t} to the DLQ if it has exhausted {@link FanOutConfig#maxAttempts()}, otherwise schedules a retry. */
    private void failOrRetry(Task t, TaskStore.Claim claim, Throwable cause, Run r) {
        int failures = claim.failures() + 1;
        if (failures >= cfg.maxAttempts()) {
            die(t, "retries exhausted after " + failures + " failures", cause, r);
            return;
        }
        scheduleRetry(t, true, failures, Duration.ZERO, r);
    }

    /**
     * Releases the task back to PENDING and re-offers it to the queue after a backoff delay (outside the capacity
     * permit, so a sleeping retry never holds downstream capacity). No-ops into a lost-lease count if another
     * worker has already taken over.
     *
     * @param countsAsFailure whether this attempt should count toward {@link FanOutConfig#maxAttempts()}
     * @param minDelay        a floor on the delay (e.g. a throttle's {@code retryAfter}); the computed backoff may exceed it
     */
    private void scheduleRetry(Task t, boolean countsAsFailure, int attemptForBackoff, Duration minDelay, Run r) {
        if (!store.releaseForRetry(t.key(), workerId, countsAsFailure)) { // lease lost: someone else owns it now
            r.lostLease.incrementAndGet();
            r.outstanding.decrementAndGet();
            return;
        }
        r.retries.incrementAndGet();
        long delay = Math.max(backoffMillis(attemptForBackoff), minDelay.toMillis());
        // Wait OUTSIDE the permit: a task sleeping before its retry must not hold downstream capacity.
        r.timer.schedule(() -> r.queue.offer(t), delay, MILLISECONDS);
    }

    /** Marks {@code t} DEAD and sends it to the {@link DeadLetterSink}, unless another worker already took the lease. */
    private void die(Task t, String reason, Throwable cause, Run r) {
        if (store.markDead(t.key(), workerId)) {
            dlq.accept(t, reason, cause);
            r.dead.incrementAndGet();
        } else {
            r.lostLease.incrementAndGet();
        }
        r.outstanding.decrementAndGet();
    }

    /** Exponential backoff with "equal jitter": half fixed, half random, so retries do not synchronise. */
    private long backoffMillis(int attempt) {
        long cap = Math.min(cfg.maxBackoff().toMillis(),
                cfg.baseBackoff().toMillis() << Math.clamp(attempt - 1, 0, 20));
        if (cap <= 0) return 0;
        return cap / 2 + ThreadLocalRandom.current().nextLong(cap / 2 + 1);
    }
}
