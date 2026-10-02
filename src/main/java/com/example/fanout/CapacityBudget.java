package com.example.fanout;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * The downstream capacity contract, shared by EVERY background caller (publish verification, backfill, reconciler).
 * Pass the same instance to every worker so they compete for one budget instead of each assuming it owns it.
 *
 * {@code reservedForUsers} is the slice of the downstream's capacity kept for real user sessions: background work
 * can never use it. A throttle response pauses everyone, because the downstream is shared.
 */
public final class CapacityBudget {

    private final Semaphore permits;
    private final Clock clock;
    private final AtomicLong pausedUntilMillis = new AtomicLong(0);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "capacity-budget-timer");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param totalCapacity    the downstream's total capacity, in concurrent in-flight calls
     * @param reservedForUsers the slice kept aside for real user sessions; background work may never touch it
     * @throws IllegalArgumentException if nothing is left for background work after the reservation
     */
    public CapacityBudget(int totalCapacity, int reservedForUsers, Clock clock) {
        int usable = totalCapacity - reservedForUsers;
        if (usable < 1) throw new IllegalArgumentException("no capacity left for background work");
        this.permits = new Semaphore(usable, true); // fair: callers are served in arrival order
        this.clock = clock;
    }

    /** Blocks until a downstream call permit is available. Always pair with {@link #release()} or {@link #releaseAfter}. */
    public void acquire() throws InterruptedException { permits.acquire(); }

    /** Returns a permit immediately, making capacity available to the next waiter. */
    public void release() { permits.release(); }

    /** Keep the permit "in use" for a while (the downstream may still be working on a call we gave up on). */
    public void releaseAfter(Duration quarantine) {
        if (quarantine.isZero() || quarantine.isNegative()) release();
        else timer.schedule((Runnable) permits::release, quarantine.toMillis(), MILLISECONDS);
    }

    /** Pauses all dispatching against this budget (every worker sharing it) until {@code d} from now, at the latest. */
    public void pauseFor(Duration d) {
        pausedUntilMillis.accumulateAndGet(clock.millis() + d.toMillis(), Math::max);
    }

    /** Blocks while a pause set by {@link #pauseFor} is still in effect; returns immediately otherwise. */
    public void awaitUnpaused() throws InterruptedException {
        long wait;
        while ((wait = pausedUntilMillis.get() - clock.millis()) > 0) {
            Thread.sleep(Math.min(wait, 50));
        }
    }
}
