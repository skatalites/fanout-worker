package com.example.fanout;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Round-robin across firms so a firm with 40,000 engagements cannot starve a firm with 40.
 * Holds ids only (tiny); for millions of tasks you would page them in instead of preloading.
 */
final class FairQueue {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Map<String, ArrayDeque<Task>> byFirm = new HashMap<>();
    private final ArrayDeque<String> ring = new ArrayDeque<>(); // firms that currently have work

    /** Enqueues {@code t} under its firm; if that firm had no pending work it rejoins the back of the ring. */
    void offer(Task t) {
        lock.lock();
        try {
            ArrayDeque<Task> q = byFirm.computeIfAbsent(t.firmId(), f -> new ArrayDeque<>());
            if (q.isEmpty()) ring.addLast(t.firmId());
            q.addLast(t);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes the next task from the firm at the front of the round-robin ring, waiting up to {@code timeout} for
     * one to appear.
     *
     * @return the next task, or {@code null} if the timeout elapsed with nothing ready
     */
    Task poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (ring.isEmpty()) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            String firm = ring.pollFirst();
            ArrayDeque<Task> q = byFirm.get(firm);
            Task t = q.pollFirst();
            if (!q.isEmpty()) ring.addLast(firm);
            return t;
        } finally {
            lock.unlock();
        }
    }
}
