package com.example.fanout;

import com.example.fanout.EngagementClient.PermanentException;
import com.example.fanout.EngagementClient.ThrottledException;
import com.example.fanout.EngagementClient.TransientException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FanOutWorkerTest {

    private static final String JOB = "job-1";

    // ---------- test doubles and helpers ----------

    /** Fast config so tests run in milliseconds. */
    private static FanOutConfig cfg(int maxAttempts) {
        return new FanOutConfig(maxAttempts, 50, Duration.ofSeconds(5),
                Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ZERO);
    }

    private static CapacityBudget budget(int capacity) {
        return new CapacityBudget(capacity, 0, Clock.systemUTC());
    }

    private static List<Task> tasks(String firm, int n) { return tasks(JOB, firm, n); }

    private static List<Task> tasks(String job, String firm, int n) {
        List<Task> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new Task(job, firm + "-e" + i, firm, Task.Reason.BACKFILL));
        return l;
    }

    private static EngagementSource sourceOf(List<Task> all) {
        return (jobId, cursor, limit) -> {
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(all.size(), from + limit);
            return new EngagementSource.Page(all.subList(from, to), to >= all.size() ? null : String.valueOf(to));
        };
    }

    /** Programmable downstream: counts calls, tracks concurrency, can fail on demand. */
    private static final class FakeClient implements EngagementClient {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger current = new AtomicInteger(), peak = new AtomicInteger();
        final long workMillis;
        volatile Predicate<String> failTransientWhen = id -> false;
        volatile Predicate<String> failPermanentWhen = id -> false;
        volatile long firstThrottleDoneAt = 0, afterThrottleStartedAt = 0;
        final AtomicInteger throttlesToSend = new AtomicInteger();
        final Duration throttleFor;

        FakeClient(long workMillis) { this(workMillis, Duration.ZERO); }
        FakeClient(long workMillis, Duration throttleFor) { this.workMillis = workMillis; this.throttleFor = throttleFor; }

        int callsFor(String id) { return calls.getOrDefault(id, new AtomicInteger()).get(); }
        int totalCalls() { return calls.values().stream().mapToInt(AtomicInteger::get).sum(); }

        @Override
        public void apply(Task task, String key) throws DownstreamException, InterruptedException {
            if (firstThrottleDoneAt != 0 && afterThrottleStartedAt == 0) afterThrottleStartedAt = System.currentTimeMillis();
            calls.computeIfAbsent(task.engagementId(), k -> new AtomicInteger()).incrementAndGet();
            int now = current.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                if (throttlesToSend.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                    if (firstThrottleDoneAt == 0) firstThrottleDoneAt = System.currentTimeMillis();
                    throw new ThrottledException(throttleFor);
                }
                if (workMillis > 0) Thread.sleep(workMillis);
                if (failPermanentWhen.test(task.engagementId())) throw new PermanentException("engagement deleted");
                if (failTransientWhen.test(task.engagementId())) throw new TransientException("503", null);
                completionOrder.add(task.engagementId());
            } finally {
                current.decrementAndGet();
            }
        }
    }

    /** A downstream that keeps working even after the caller gave up (cannot be cancelled), like a slow remote load. */
    private static final class StubbornClient implements EngagementClient {
        final AtomicInteger realInFlight = new AtomicInteger(), peak = new AtomicInteger();
        final long workMillis;
        StubbornClient(long workMillis) { this.workMillis = workMillis; }

        @Override
        public void apply(Task task, String key) {
            peak.accumulateAndGet(realInFlight.incrementAndGet(), Math::max);
            long end = System.nanoTime() + workMillis * 1_000_000;
            for (long left = end - System.nanoTime(); left > 0; left = end - System.nanoTime()) {
                try { Thread.sleep(Math.max(1, left / 1_000_000)); } catch (InterruptedException ignored) { /* keeps working */ }
            }
            realInFlight.decrementAndGet();
        }
    }

    private static final class RecordingDlq implements DeadLetterSink {
        final List<String> ids = Collections.synchronizedList(new ArrayList<>());
        @Override public void accept(Task t, String reason, Throwable cause) { ids.add(t.engagementId() + " | " + reason); }
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-03-10T12:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private FanOutWorker worker(List<Task> all, TaskStore store, EngagementClient client, RecordingDlq dlq,
                                JobGuard guard, CapacityBudget budget, FanOutConfig cfg, Clock clock) {
        return new FanOutWorker(sourceOf(all), store, client, dlq, guard, budget, cfg, clock);
    }

    private FanOutWorker worker(List<Task> all, TaskStore store, EngagementClient client, RecordingDlq dlq,
                                CapacityBudget budget, FanOutConfig cfg) {
        return worker(all, store, client, dlq, id -> true, budget, cfg, Clock.systemUTC());
    }

    // ---------- tests ----------

    @Test
    void processesEachEngagementEffectivelyOnce_evenIfTheJobIsRunTwice() throws Exception {
        List<Task> all = new ArrayList<>();
        for (String f : List.of("A", "B", "C", "D", "E")) all.addAll(tasks(f, 40));
        FakeClient client = new FakeClient(1);
        FanOutWorker w = worker(all, new InMemoryTaskStore(), client, new RecordingDlq(), budget(20), cfg(3));

        JobSummary first = w.run(JOB);
        JobSummary second = w.run(JOB); // e.g. the job was triggered twice

        assertEquals(200, first.done());
        assertEquals(200, second.skippedAlreadyTerminal());
        assertEquals(200, client.totalCalls(), "downstream must be called once per engagement, not once per run");
        all.forEach(t -> assertEquals(1, client.callsFor(t.engagementId())));
    }

    @Test
    void neverExceedsTheAgreedDownstreamCapacity() throws Exception {
        FakeClient client = new FakeClient(5);
        FanOutWorker w = worker(tasks("A", 100), new InMemoryTaskStore(), client, new RecordingDlq(), budget(7), cfg(3));

        w.run(JOB);

        assertTrue(client.peak.get() <= 7, "peak concurrency was " + client.peak.get());
        assertTrue(client.peak.get() > 1, "should actually run in parallel");
    }

    @Test
    void twoJobsSharingOneBudgetStayWithinIt() throws Exception {
        CapacityBudget shared = new CapacityBudget(4, 1, Clock.systemUTC()); // 3 usable, 1 reserved for real users
        FakeClient client = new FakeClient(5);
        InMemoryTaskStore store = new InMemoryTaskStore();
        FanOutWorker backfill = worker(tasks("job-backfill", "A", 30), store, client, new RecordingDlq(), id -> true, shared, cfg(3), Clock.systemUTC());
        FanOutWorker reconcile = worker(tasks("job-reconcile", "B", 30), store, client, new RecordingDlq(), id -> true, shared, cfg(3), Clock.systemUTC());

        Thread t1 = new Thread(() -> { try { backfill.run("job-backfill"); } catch (InterruptedException ignored) { } });
        Thread t2 = new Thread(() -> { try { reconcile.run("job-reconcile"); } catch (InterruptedException ignored) { } });
        t1.start(); t2.start(); t1.join(); t2.join();

        assertEquals(60, client.totalCalls());
        assertTrue(client.peak.get() <= 3, "combined peak was " + client.peak.get() + ", budget is 3");
    }

    @Test
    void aTimedOutCallKeepsHoldingCapacityUntilTheQuarantineEnds() throws Exception {
        // every call takes 100 ms of "real" work, but we only wait 30 ms; the remote keeps working after we give up
        StubbornClient client = new StubbornClient(100);
        FanOutConfig c = new FanOutConfig(2, 50, Duration.ofMillis(30),
                Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ofMillis(150));
        RecordingDlq dlq = new RecordingDlq();
        FanOutWorker w = worker(tasks("A", 2), new InMemoryTaskStore(), client, dlq, budget(1), c);

        JobSummary s = w.run(JOB);

        assertEquals(2, s.dead(), "every attempt times out, so both tasks end in the DLQ");
        assertEquals(1, client.peak.get(), "real downstream concurrency must never exceed the budget of 1");
    }

    @Test
    void throttleResponsesDoNotConsumeRetryAttempts() throws Exception {
        // 5 throttles, THEN one real transient failure, THEN success. Only 2 real failures are allowed (maxAttempts = 2).
        // If throttles were counted as failures, that single real failure would push the task into the DLQ.
        FakeClient client = new FakeClient(0, Duration.ofMillis(2));
        client.throttlesToSend.set(5);
        AtomicInteger realFailuresLeft = new AtomicInteger(1);
        client.failTransientWhen = id -> realFailuresLeft.getAndDecrement() > 0;
        RecordingDlq dlq = new RecordingDlq();
        FanOutWorker w = worker(tasks("A", 1), new InMemoryTaskStore(), client, dlq, budget(1), cfg(2));

        JobSummary s = w.run(JOB);

        assertEquals(1, s.done(), "5 throttles + 1 real failure must not exhaust a budget of 2 real failures");
        assertEquals(0, s.dead());
        assertEquals(5, s.throttledRetries());
        assertEquals(7, client.callsFor("A-e0"), "5 throttled calls + 1 failed call + 1 successful call");
    }

    @Test
    void sustainedThrottlingIsBoundedAndEventuallyGoesToTheDlq() throws Exception {
        FakeClient client = new FakeClient(0, Duration.ofMillis(1));
        client.throttlesToSend.set(1000);
        FanOutConfig c = new FanOutConfig(3, 3, Duration.ofSeconds(5),
                Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofSeconds(1), Duration.ZERO);
        RecordingDlq dlq = new RecordingDlq();
        FanOutWorker w = worker(tasks("A", 1), new InMemoryTaskStore(), client, dlq, budget(1), c);

        JobSummary s = w.run(JOB);

        assertEquals(1, s.dead());
        assertTrue(dlq.ids.getFirst().contains("throttled too many times"));
    }

    @Test
    void retriesTransientFailuresAndThenSucceeds() throws Exception {
        FakeClient client = new FakeClient(0);
        AtomicInteger failuresLeft = new AtomicInteger(2);
        client.failTransientWhen = id -> id.equals("A-e1") && failuresLeft.getAndDecrement() > 0;
        RecordingDlq dlq = new RecordingDlq();
        FanOutWorker w = worker(tasks("A", 3), new InMemoryTaskStore(), client, dlq, budget(3), cfg(5));

        JobSummary s = w.run(JOB);

        assertEquals(3, s.done());
        assertEquals(2, s.retriesScheduled());
        assertEquals(3, client.callsFor("A-e1"));
        assertTrue(dlq.ids.isEmpty());
    }

    @Test
    void permanentFailureGoesStraightToTheDlqWithoutRetries() throws Exception {
        FakeClient client = new FakeClient(0);
        client.failPermanentWhen = id -> id.equals("A-e0");
        RecordingDlq dlq = new RecordingDlq();
        InMemoryTaskStore store = new InMemoryTaskStore();
        FanOutWorker w = worker(tasks("A", 3), store, client, dlq, budget(3), cfg(5));

        JobSummary s = w.run(JOB);

        assertEquals(1, s.dead());
        assertEquals(2, s.done());
        assertEquals(1, client.callsFor("A-e0"));
        assertEquals(TaskStore.State.DEAD, store.stateOf(JOB + ":A-e0").orElseThrow());
        assertEquals(1, dlq.ids.size());
    }

    @Test
    void exhaustedRetriesEndUpInTheDlq() throws Exception {
        FakeClient client = new FakeClient(0);
        client.failTransientWhen = id -> true;
        RecordingDlq dlq = new RecordingDlq();
        FanOutWorker w = worker(tasks("A", 2), new InMemoryTaskStore(), client, dlq, budget(2), cfg(3));

        JobSummary s = w.run(JOB);

        assertEquals(2, s.dead());
        assertEquals(3, client.callsFor("A-e0"), "maxAttempts counts the first try");
        assertEquals(2, dlq.ids.size());
    }

    @Test
    void aWithdrawnJobIsNotProcessed() throws Exception {
        FakeClient client = new FakeClient(0);
        FanOutWorker w = worker(tasks("A", 10), new InMemoryTaskStore(), client, new RecordingDlq(),
                id -> false, budget(5), cfg(3), Clock.systemUTC());

        JobSummary s = w.run(JOB);

        assertEquals(10, s.cancelled());
        assertEquals(0, client.totalCalls());
    }

    @Test
    void aHugeFirmDoesNotStarveSmallFirms() throws Exception {
        List<Task> all = new ArrayList<>(tasks("BIG", 200));
        all.addAll(tasks("S1", 5));
        all.addAll(tasks("S2", 5));
        FakeClient client = new FakeClient(0);
        FanOutWorker w = worker(all, new InMemoryTaskStore(), client, new RecordingDlq(), budget(1), cfg(3)); // sequential: order is observable

        w.run(JOB);

        int lastSmall = 0;
        for (int i = 0; i < client.completionOrder.size(); i++) {
            if (!client.completionOrder.get(i).startsWith("BIG")) lastSmall = i;
        }
        assertTrue(lastSmall < 20, "small firms finished at position " + lastSmall + " of 210; they should interleave with BIG");
    }

    @Test
    void aCrashedWorkersTaskIsReclaimedOnlyAfterItsLeaseExpires() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryTaskStore store = new InMemoryTaskStore();
        List<Task> all = tasks("A", 1);
        // A previous worker claimed the task and then died (never marked it done):
        store.tryClaim(all.getFirst().key(), "dead-worker", clock.instant(), clock.instant().plusSeconds(60));
        FakeClient client = new FakeClient(0);
        FanOutWorker w = worker(all, store, client, new RecordingDlq(), id -> true,
                new CapacityBudget(1, 0, clock), cfg(3), clock);

        JobSummary whileLeaseValid = w.run(JOB);
        assertEquals(1, whileLeaseValid.skippedHeldByOther());
        assertEquals(0, client.totalCalls());

        clock.now = clock.now.plusSeconds(61); // lease expired
        JobSummary afterExpiry = w.run(JOB);
        assertEquals(1, afterExpiry.done());
        assertEquals(1, client.totalCalls());
    }

    @Test
    void aZombieWorkerCannotOverwriteTheNewOwnersResult() {
        MutableClock clock = new MutableClock();
        InMemoryTaskStore store = new InMemoryTaskStore();
        String key = "job-1:A-e0";
        store.tryClaim(key, "old", clock.instant(), clock.instant().plusSeconds(10));
        clock.now = clock.now.plusSeconds(11);
        store.tryClaim(key, "new", clock.instant(), clock.instant().plusSeconds(10)); // took over

        assertFalse(store.markDone(key, "old"), "fenced: old owner no longer holds the task");
        assertTrue(store.markDone(key, "new"));
    }

    @Test
    void aThrottleResponsePausesDispatchingOfEveryTask() throws Exception {
        FakeClient client = new FakeClient(0, Duration.ofMillis(300));
        client.throttlesToSend.set(1);
        FanOutWorker w = worker(tasks("A", 3), new InMemoryTaskStore(), client, new RecordingDlq(), budget(1), cfg(5));

        JobSummary s = w.run(JOB);

        assertEquals(3, s.done());
        long gap = client.afterThrottleStartedAt - client.firstThrottleDoneAt;
        assertTrue(gap >= 250, "next downstream call started only " + gap + "ms after the throttle response");
    }
}
