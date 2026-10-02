package com.example.fanout;

/** What happened in one run. Every task ends in exactly one of the terminal buckets. */
public record JobSummary(int total, int done, int dead, int cancelled,
                         int skippedAlreadyTerminal, int skippedHeldByOther, int lostLease,
                         int retriesScheduled, int throttledRetries) {}
