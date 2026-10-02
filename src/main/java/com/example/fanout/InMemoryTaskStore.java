package com.example.fanout;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reference implementation for tests/local runs. */
public final class InMemoryTaskStore implements TaskStore {

    private record Rec(State state, int failures, String owner, Instant leaseUntil) {}

    private final ConcurrentHashMap<String, Rec> map = new ConcurrentHashMap<>();

    /** @see TaskStore#tryClaim */
    @Override
    public Claim tryClaim(String key, String owner, Instant now, Instant leaseUntil) {
        AtomicReference<Claim> result = new AtomicReference<>();
        map.compute(key, (k, cur) -> {
            boolean free = cur == null || cur.state() == State.PENDING;
            boolean expired = cur != null && cur.state() == State.IN_PROGRESS && !cur.leaseUntil().isAfter(now);
            if (free || expired) {
                int failures = cur == null ? 0 : cur.failures() + (expired ? 1 : 0); // a lost attempt counts as a failure
                result.set(new Claim(Outcome.CLAIMED, failures, State.IN_PROGRESS));
                return new Rec(State.IN_PROGRESS, failures, owner, leaseUntil);
            }
            Outcome o = cur.state() == State.IN_PROGRESS ? Outcome.HELD_BY_OTHER : Outcome.ALREADY_TERMINAL;
            result.set(new Claim(o, cur.failures(), cur.state()));
            return cur;
        });
        return result.get();
    }

    /** Shared fenced transition: only applies if {@code owner} currently holds an IN_PROGRESS claim on {@code key}. */
    private boolean transition(String key, String owner, State to, boolean countsAsFailure) {
        AtomicBoolean ok = new AtomicBoolean();
        map.computeIfPresent(key, (k, cur) -> {
            if (cur.state() == State.IN_PROGRESS && Objects.equals(cur.owner(), owner)) {
                ok.set(true);
                return new Rec(to, cur.failures() + (countsAsFailure ? 1 : 0), null, null);
            }
            return cur;
        });
        return ok.get();
    }

    /** @see TaskStore#markDone */
    @Override public boolean markDone(String key, String owner)      { return transition(key, owner, State.DONE, false); }
    /** @see TaskStore#markDead */
    @Override public boolean markDead(String key, String owner)      { return transition(key, owner, State.DEAD, false); }
    /** @see TaskStore#markCancelled */
    @Override public boolean markCancelled(String key, String owner) { return transition(key, owner, State.CANCELLED, false); }
    /** @see TaskStore#releaseForRetry */
    @Override public boolean releaseForRetry(String key, String owner, boolean countsAsFailure) {
        return transition(key, owner, State.PENDING, countsAsFailure);
    }

    /** Test/inspection helper: the current state of {@code key}, or empty if it was never claimed. */
    public Optional<State> stateOf(String key) {
        return Optional.ofNullable(map.get(key)).map(Rec::state);
    }
}
