package com.example.fanout;

/** Lets the worker stop pointless work, e.g. a PUBLISH_VERIFY job whose publish was withdrawn meanwhile. */
public interface JobGuard {
    boolean isActive(String jobId);
}
