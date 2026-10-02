# Fan-out worker (Part 2)

A job (a backfill, a reconciliation sample, or a post-publish verification sample) becomes N per-engagement tasks, and
each task calls a **slow downstream (~1 min per engagement, owned by another team, limited capacity)**. This worker
runs those tasks safely: bounded, fair, idempotent, retrying and resumable.

## What the worker is for (and what it is not)
## What the worker is for (and what it is not)

In the design, "is this engagement pending?" is **derived** from the index and the version graph, so a template publish
creates no per-engagement work for engagements the index already knows. The slow call is needed only to **resolve**
an engagement: load it and refresh its index row. That happens in three situations, and the worker serves all of them:

| `Task.Reason` | Trigger | Where the engagement list comes from |
|---|---|---|
| `BACKFILL` | engagements the index has never seen (`UNKNOWN / NOT_BACKFILLED`) | a cheap inventory of engagement ids and firms (**assumption:** the EMS can list ids without loading them) |
| `RECONCILE` | periodic risk-biased sample | rows the index believes are `KNOWN` |
| `PUBLISH_VERIFY` | after a publish, a sample of that product's engagements | rows the index believes are `KNOWN` for that product |

A publish cannot enumerate engagements that were never loaded (they have no product or market yet), which is why those
are handled by `BACKFILL`, not by the publish itself. Tasks are therefore keyed by **job**, not only by publish.

## Run the tests

```
mvn test
```
(Java 21.) `mvn clean install` runs the 14 tests and passes (BUILD SUCCESS). 
Earlier, the tests were also run with plain `javac` + the JUnit console launcher, and two of them were checked to fail 
when the property they protect is removed (no quarantine after a timeout; throttles counted as failures).

## Contracts

| Port | Meaning | Production mapping |
|---|---|---|
| `EngagementSource` | pages through the engagements of a job | inventory query (backfill) or sampled query on the regional Pending Index |
| `EngagementClient` | the slow call; **must be idempotent on the key** | HTTP/gRPC to the EMS load API |
| `TaskStore` | per-task state, claim, lease, fencing, failure count | one DynamoDB item per `(jobId, engagementId)`, conditional writes |
| `CapacityBudget` | the shared downstream capacity contract | one instance shared by every background caller |
| `DeadLetterSink` | tasks that cannot succeed | SQS DLQ + alarm + redrive tool |
| `JobGuard` | is this job still valid? | e.g. publish withdrawn or superseded |

## Key trade-offs

1. **Effectively-once, not exactly-once.** Nothing gives exactly-once across a network call. A task is claimed in the
   store *before* calling; the call is at-least-once; `jobId:engagementId` is sent as an idempotency key so a retry (or a
   timeout where the downstream still finished) is harmless. This is a **requirement on the other team**.
2. **One shared capacity budget.** Workers for different jobs draw from the same `CapacityBudget`, and a slice
   (`reservedForUsers`) is never used by background work, because the downstream also serves real users.
3. **A timeout does not free capacity.** On timeout the downstream may still be working, so the permit stays held for a
   quarantine (`timeoutQuarantine`, about one typical call). Cost: lower throughput after timeouts. Benefit: real
   concurrency never exceeds the budget. If the downstream offers real cancellation, use it and shorten the quarantine.
4. **Throttles are not failures.** A "throttled" answer pauses *all* dispatching (for every worker sharing the budget)
   and does not consume the retry budget, so sustained throttling cannot send healthy tasks to the DLQ. It is bounded
   separately (`maxThrottleRetries`), then the task goes to the DLQ with its own reason.
5. **Leases, not heartbeats.** A claim lasts `callTimeout + margin`. If a worker dies, the lease expires and another
   run takes over; an expired lease counts as a failure, which protects against poison tasks.
6. **Fencing.** Only the current owner can mark done / retry / dead, so a "zombie" worker that lost its lease cannot
   overwrite the new owner's result.
7. **Fairness over per-firm throughput.** Round-robin across firms: a firm with 40,000 engagements cannot starve one
   with 40. Cost: a single huge firm finishes later than with plain FIFO.
8. **Retry policy.** Exponential backoff with equal jitter; retry waits happen **outside** the permit so sleeping tasks
   never hold capacity. Permanent errors skip retries; exhausted retries go to the DLQ.
9. **Withdrawn jobs stop early** (`JobGuard`), so no 1-minute calls are spent on work nobody needs.

## Known limits (deliberately left out)

- **The budget is per process.** Several instances need either a static split of the capacity or a distributed limiter
  (e.g. a token bucket in DynamoDB/Redis). Not implemented.
- **The retry schedule is in memory.** After a crash the store still knows what is unfinished, but something must
  re-trigger the job (a scheduler that re-runs jobs with non-terminal tasks). Not implemented.
- **No cross-job de-duplication.** Two jobs resolving the same engagement at the same time both call the downstream
  (harmless because it is idempotent, but wasteful).
- **Task ids are preloaded in memory** (fine up to ~100k ids); for millions, stream pages instead.
- **No metrics export.** `JobSummary` carries the counters; in production they become CloudWatch metrics and alarms.
- `InMemoryTaskStore` is a reference implementation for tests, not a production store.

## Tests (14)

Effectively-once even when the job runs twice · never exceeds capacity · two jobs sharing one budget stay within it ·
a timed-out call keeps holding capacity during the quarantine · throttles do not consume retry attempts · sustained
throttling is bounded and ends in the DLQ · transient retry then success · permanent error straight to the DLQ ·
exhausted retries to the DLQ · withdrawn job not processed · big firm does not starve small firms · crashed worker's
task reclaimed only after lease expiry · zombie worker is fenced · throttle pauses all dispatching.
