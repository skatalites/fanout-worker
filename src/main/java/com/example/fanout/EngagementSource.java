package com.example.fanout;

import java.util.List;

/**
 * Enumerates the engagements of a job.
 * BACKFILL: from a cheap inventory of engagement ids and firms (assumption: the EMS can list ids without loading them).
 * RECONCILE / PUBLISH_VERIFY: a risk-biased sample of KNOWN rows from the regional Pending Index.
 */
public interface EngagementSource {

    record Page(List<Task> tasks, String nextCursor) {}

    /**
     * Fetches one page of engagements for {@code jobId}.
     *
     * @param cursor {@code null} to start from the beginning, otherwise the {@code nextCursor} of the previous page
     * @param limit  maximum number of tasks to return in this page
     * @return a page whose {@code nextCursor} is {@code null} when this was the last page
     */
    Page next(String jobId, String cursor, int limit);

}
