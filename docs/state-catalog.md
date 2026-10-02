# State Catalog

A short reference: every state used in the design, what it means, and whether we **save** it or **calculate** it.

## 1. Main idea: save the facts, calculate the rest

Most states are calculated each time and never saved. We save only a few facts. This is why a withdrawal is easy: change one row in the version list and every dashboard is right at once, with no mass update.

| Saved (the facts) | Calculated (never saved) |
|---|---|
| Whether a version is published or withdrawn | What the dashboard shows |
| The version an engagement is on, and the versions it declined | "Pending" and the alerts |
| Whether we are sure of that (known or unknown) | Progress counts (total, done, in progress, failed) |
| The status of each task and each summary | |

## 2. The states

### A. Facts we save

| # | Concept | States | What it means |
|---|---|---|---|
| 1 | **Template version** | `PUBLISHED`, `WITHDRAWN` | Withdrawn is final; a fix is a new version. (Drafts exist before publishing, but are out of scope.) |
| 2 | **Decision on a version** | `APPLIED`, `DECLINED` (no decision yet: nothing saved) | Declining is about that exact version and the summary the user saw. It does not mean "never update". |
| 3 | **Engagement** | `ACTIVE`, `ARCHIVED`, `DELETED` | Archived and deleted engagements leave the dashboard and are skipped by the backfill. |
| 4 | **What the Pending Index knows** | `KNOWN`; `UNKNOWN` with a reason: `NOT_BACKFILLED`, `SUSPECT`, `LOAD_FAILED` | Known: we are sure which version it is on. Suspect: the sampling check found a mismatch. |
| 5 | **Summary** | `GENERATING`, `READY`, `CHECKS_FAILED` | A ready summary is never edited; a correction is a new one. If the checks fail, only the exact list of changes is shown. |
| 6 | **Rollout per firm** | `OFF`, `SHADOW`, `ON` | Shadow: answers are calculated but users don't see them. To roll back, go back to off. |

### B. What the user sees (calculated)

| # | Concept | States | What it means |
|---|---|---|---|
| 7 | **Dashboard status** | `VERIFYING`, `BASE_WITHDRAWN`, `NO_TARGET`, `UP_TO_DATE`, `DECLINED`, `UPDATE_PENDING` | Chosen in the order in section 3, so "up to date" is never the default. Apply and Decline appear only on `UPDATE_PENDING`. |

### C. Behind the scenes

| # | Concept | States | What it means |
|---|---|---|---|
| 8 | **Event received** | `PROCESSED`, `DUPLICATE`, `OLD`, `GAP`, `RETRYING`, `FAILED` | `GAP` is only a note: the next event has the full state, so the row stays known. `FAILED` goes to the DLQ with an alert. |
| 9 | **Worker task** | `PENDING`, `IN_PROGRESS`, `DONE`, `DEAD`, `CANCELLED` | Matches the Part 2 code. "Retrying" is not a state: it is `PENDING` with failures above 0. Throttle answers don't count as failures. |
| 10 | **Job (campaign)** | `CREATED`, `RUNNING`, `PAUSED`, `COMPLETED`, `COMPLETED_WITH_FAILURES`, `CANCELLED` | Calculated from the task states. Users only see "we could not verify this yet"; the details are for the team. The Part 2 code reports counts, not these job states. |
| 11 | **Sampling check result** | `MATCH`, `MISMATCH`, `CHECK_FAILED` | A mismatch fixes the engagement and marks similar ones `SUSPECT`. The sample favors risky rows (withdrawn version, not opened for a long time). |

## 3. Dashboard status: the order we check

Go from top to bottom; the first one that fits wins.

1. We don't know which version it is on → **`VERIFYING`** (never "up to date").
2. The version it is on was withdrawn → **`BASE_WITHDRAWN`** (an alert only; see section 5).
3. There is no valid version in its market → **`NO_TARGET`**.
4. The latest valid version is the one it is on → **`UP_TO_DATE`**.
5. The user declined the latest valid version → **`DECLINED`** (not shown as pending).
6. Otherwise → **`UPDATE_PENDING`**.

The latest valid version is the newest published, not withdrawn, version in the engagement's market.

Why this order: the two mistakes don't cost the same. A false "pending" is a small annoyance; a false "up to date" on an audit file is a real risk. So doubt always leans toward the cheap mistake.

## 4. When two sides disagree, who wins

| Situation | Who wins | What happens |
|---|---|---|
| The Pending Index says v6, the engagement is really on v7 | The engagement | Fixed when someone opens it, or by the sampling check. |
| The index says pending, but it was already applied | The engagement | A false alarm: annoying, but the cheap mistake. |
| An old event arrives | The index | Ignored. |
| An event is missing from the sequence | The next event | It has the full state, so the row stays known. The gap is logged. |
| A summary is ready, then its version is withdrawn | The summary stays valid | Only the offer is recalculated; nothing is deleted. |
| An engagement stays unknown for too long | The system | An alarm, or else the backfill died silently. |
| The user applied v7, v7 was withdrawn, and we missed the "applied" event | The engagement | The dangerous mistake: it shows "up to date" on a withdrawn version. Covered by fixing entries on open and by the sampling check. |

**Order of trust:** the Template Store over the copy of the version list; the engagement system over the Pending Index; the Pending Index over the dashboard.

## 5. Decision: a withdrawn version that was already applied

Example: the engagement applied v7, v7 was withdrawn, and the newest valid version is v6. The simple rule "offer the newest valid version" would offer to go **back** to v6.

**Decision:** show an alert only, with the reason for the withdrawal if we have it, and no rollback. People decide. Reasons:
- Applying or undoing template content is out of scope.
- The engagement may have been edited after v7 was applied, and an automatic rollback could overwrite real work.
- The right fix (go back, correct by hand, or wait for a v7.1) depends on the content team.

Status: **settled.** It is the same rule as in the design document.
