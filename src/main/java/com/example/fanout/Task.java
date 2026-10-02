package com.example.fanout;

/**
 * One unit of work: "resolve this engagement" (load it through the slow downstream and refresh its index row).
 * The same worker serves three kinds of jobs, which is why the key is the job, not only the publish.
 */
public record Task(String jobId, String engagementId, String firmId, Reason reason) {

    public enum Reason {
        /** Fill the index for an engagement we have never loaded (state UNKNOWN / NOT_BACKFILLED). */
        BACKFILL,
        /** Reconciler sample: verify an engagement the index believes it knows. */
        RECONCILE,
        /** Risk-biased sample of a product's engagements after a template publish. */
        PUBLISH_VERIFY
    }

    /**
     * Idempotency key, unique per (jobId, engagementId). Also sent downstream so the other team can
     * de-duplicate on their side; this is what makes a retried or re-run call safe.
     */
    public String key() {
        return jobId + ":" + engagementId;
    }

}
