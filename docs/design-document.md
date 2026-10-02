# Template Update: Design Document

**Glossary.** See the attached `glossary.md` for the terms shown in *italics*.

**Scope.** Show *users* which *engagement files* have a pending *product template* update, with a summary they can read, for 800,000 active engagements / ~4,000 firms / 40 products, on live systems with no maintenance window. Applying the update is out of scope. Diagram: `architecture.drawio`.

## 0. Summary of the design

1. **Pending isn't stored; it's calculated.** We keep two small lists: the latest valid version for each product and market, and the version each engagement is on (and what it declined). Comparing them tells us what's pending, so a publishing doesn't touch any engagement.
2. **The *Pending Index* never needs the 1-minute load to answer a user.** It is fed by events from the existing systems, and filled for existing engagements by a background *backfill* at a controlled pace, most active firms first. Engagements not yet known show as **unknown**, never as "up to date".
3. **One summary per *version pair*, not per engagement.** A version pair is the version an engagement is on and the version it would move to (e.g. `v5-CA → v7-CA`). We write the summary once (per language) and reuse it. An exact list of what changed is the source of truth; the LLM only rewrites it in plain language, and we check the result. Each summary is saved as an immutable record, so it can be defended months later.

## 1. Architecture and data ownership

| Component | Origin | Scope | What it does |
|---|---|---|---|
| Template Store | Existing | Global | Keeps every template version. We assume it can send an event on publish or withdrawal |
| EMS + Engagement DBs | Existing | Regional | Keep each engagement and the user's decisions. We assume the EMS can send an event with the full state on every change |
| Version Graph Service | New | Global | Lists versions per product and market, and whether each is published or withdrawn |
| Summary Service + Store | New | Global | Writes summaries and keeps them as immutable records; one copy serves all firms |
| Event Ingestion + Pending Index | New | Regional | Only ids and versions, built from events; can be rebuilt any time |
| Query API | New | Regional | What the UI reads: ids and flags |
| Worker (plus the backfill and the sampling check, which create its jobs) | New | Regional | Controlled calls to the EMS load, under one shared limit. Part 2 builds it |

**Per engagement, we store:** its id and firm, template and market, the version it is on, the versions it declined, known or unknown, and a counter to ignore old events.

**When is an engagement pending?** When the latest valid version in its market differs from the one it is on, and the user hasn't declined it. Each market has its own line of versions, so a Canadian engagement never gets a US version. Unknown engagements show "verifying".

- **Declining** means "not this version". If v5 is declined and v7 comes later, v7 is a new offer, with a summary from the version the engagement is on and a note that it includes v5.
- **Several updates piling up:** one offer, the latest valid version, with the total summary.
- **A version is withdrawn:** the offer goes back to the latest valid one. If the user already declined that one, nothing is pending.
- **An applied version is withdrawn:** an alert, not an automatic rollback, which could overwrite legitimate edits. People decide.

Because "pending" is a comparison with a very small list, a publishing shows up as soon as that list refreshes.

## 2. Correctness and production evolution

**Each event carries the full picture, not just the change:** the engagement's version, what it declined, and a counter. We apply it only if the counter is newer. So repeated or out-of-order events do no harm, and losing one in the middle does not matter. The only real risk is losing the *latest* event.

**If the EMS cannot guarantee an event for every change** (we assume it can), some could be lost. We would lean more on the safety nets below, and report how often the sampling check finds a mismatch instead of promising there are none.

**Safety nets.**
- **Opening an engagement:** the system loads it anyway, so we read its real version and fix our entry.
- **Sampling check:** after each publish, and daily, we load a small sample and compare.
- **Engagements never checked** are unknown and filled by the backfill. A publishing cannot find them: until one is loaded, we don't know its product or market.

**Rollout (nothing is replaced or taken offline).** 1) Add the events, unused. 2) Start reading them. 3) Fill in the rest: on open, and with the backfill, off-peak and able to resume. 4) Run silently: compute answers, hide them, compare with the sampling check. 5) Turn on firm by firm, smallest first; to roll back, switch off and rebuild.

**If something fails.** Every step is safe to repeat. Events that keep failing go to a separate queue (a *DLQ*) with an alert. If a worker dies, another takes over. If a region goes down, only its firms are affected.

## 3. Scale, cost and operations

**Why not scan.** 800,000 engagements × 1 minute = **13,333 hours** of loading. One publish affects one product: about 800,000 / 40 = 20,000 engagements (if spread evenly), which is about **333 hours one at a time**, or **6.7 hours** with 50 at a time (assumed capacity of the other team). Either way it is nowhere near "seconds", and it competes with real users.

| What | How we calculated it | Result |
|---|---|---|
| Backfill time (once) | 13,333 hours; with 50 at a time: 267 hours | about 11 days |
| Backfill cost (once) | 13,333 server hours × about $0.10 (assumed) | about $1,300 |
| Sampling checks | 100 per publish × 40 a week + 500 a day = 7,500 loads a week | about $55 a month |
| Pending Index | 800,000 small rows; worst case 24 million writes a month | up to $30 a month |
| Summaries | 1,200 a week (6,000 if every market publishes weekly) × about 2 cents | $105 a month (up to $520) |
| **Total each month** | | **about $190; worst case $720** |

Summaries are the biggest recurring cost. We can't compare with «$X» because it isn't given. Not included: reviewers' time. Assumed: 50 loads at a time, $0.10 per server hour, about 5,000 tokens in and 1,000 out per summary, 5 markets. Prices: AWS and Anthropic list prices, October 2026.

**Targets.**
- **Indicator:** up to date within 60 seconds of a publishing, in 99 of 100 cases (known engagements).
- **Summary:** ready within 5 minutes for the main version line, in 95 of 100 cases; the exact list of changes shows right away.
- **Coverage:** after the backfill, at least 99.5% of active engagements are known.
- **Wrong "up to date":** at most 1 in 1,000. About 3,000 sampled engagements with no mistake let us say, with good confidence, that the rate is below that (about 1 hour of loading with 50 at once). Proving 1 in 10,000 would need 10 times more, which is not practical.
- **Failed events:** the failure queue should be empty; if not for 15 minutes, an alert.

**What we watch.** Per publish and region: how many engagements are known, indicator speed, waiting or failed events, mismatches found by the sampling check, failed summary checks, and cost.

**Data residency.** Each region has its own Pending Index, Query API and workers, holding only ids and versions: no client content. The global part holds only template content and summaries, the same for every firm.

## 4. Summaries: how we write and check them

**How we write them.**
1. The existing diff tool lists what changed between the two versions.
2. We turn it into a clear list of changes (added, removed, changed). This list is **always shown**.
3. The LLM rewrites it in plain language, each sentence pointing to the changes it covers. We never send client data, only template differences.
4. Automatic checks: every change covered once, and no number or standard that isn't in the list. Important changes (removed required procedures, changed standards) use fixed wording and go to expert review. If a check fails, only the list is shown.

**Defending a March summary in November.** An LLM can word the same thing differently each time, so we keep what was shown instead of rewriting later. Each summary is saved with both versions, a fingerprint of the changes, the model and instructions used, the language, the date and the check results. It is never edited; a correction is a new record. When a user applies or declines, we save which summary they saw. We assume 7 years of retention, to confirm.

**Quality checks.** Before any change to the model or instructions, we test on real examples labeled by the content team: no unsupported claim, at least 99% of changes covered, including tricky cases where meaning could flip. After launch, experts review a sample (about 5%) and all important changes, and we watch failed checks and feedback.

## 5. Trade-offs, assumptions, and what I would challenge

**Trade-offs.**
- Calculating "pending" instead of storing it: simpler and right after a withdrawal, but it needs a comparison on every read.
- Events with the full state: they survive losses and disorder, but are bigger.
- Writing summaries on first request: cheaper, but the first person to ask for a rare version pair waits.
- Always showing the exact list: less polished, much easier to audit.

**Assumptions.** The EMS can send events with a counter; it can list engagement ids and firms without loading them; its load call is safe to repeat and handles about 50 calls at a time; a market is a line of versions; 5 markets and 3 languages; "active" means opened or changed in the last 12 months.

**The requirement I would challenge: "real time, within seconds", for everything.** We can deliver about a minute, often seconds, for the indicator on known engagements. We cannot honestly do seconds for engagements not yet checked (days of backfill) or for summaries (they need checks and, for big changes, review). I would use three levels: the indicator within a minute, summaries within minutes, and "verifying" for the rest. Rushing summaries is the wrong place to take risk in an audit product.

**Riskiest to get wrong: showing "up to date" when it isn't.** In an audit, a missed update is worse than a false alarm. We reduce it by never showing "up to date" for unknown engagements, events with the full state, fixing entries on open, and the sampling check with its own target.

**Left out on purpose.** Applying updates; the UI; the review process for summaries; languages beyond the first; login and permissions; email alerts; automatic rollback of a withdrawn applied version (alert only); copying summaries across regions; cost alerts; sharing one limit across several workers.
