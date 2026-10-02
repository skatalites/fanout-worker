# Glossary

Terms shown in *italics* in the design document are defined here, plus a few others it uses.

**Source:** *Exercise* = comes from the take-home. *Design* = our own choice or name. *Industry* = a common engineering term.

| Term | Meaning | Source |
|---|---|---|
| **Users** | Audit, assurance, risk and compliance professionals at accounting firms. They are not technical, so what we show them must be easy to read. | Exercise |
| **Firm** | A customer accounting firm. About 4,000 of them; most have few engagements (median ~40) and the largest has ~40,000. | Exercise |
| **Product** | One of about 40 product lines. Its template is updated about once a week. | Exercise |
| **Product template** | The structured content (JSON) that engagement files are created from. Content teams update it, market by market. | Exercise |
| **Engagement file** | All the work a firm does for one client over a year. It is created from a product template and remembers which template and version. It is client-confidential. About 800,000 are active. | Exercise |
| **Market** | A regional version of a template, kept by its own content team (e.g. US, Canada). Each market has its own line of versions. What a market covers exactly (country, state, ...) is an assumption. | Exercise (exact meaning: assumption) |
| **Version** | One published state of a template. We assume a published version is never changed. | Exercise (never changed: assumption) |
| **Withdrawn version** | A published version that was taken back later, for example because of an error. | Exercise |
| **Latest valid version** | The newest published version in an engagement's market that has not been withdrawn. | Design |
| **Update** | A newer version offered to an engagement. The user applies it or declines it. | Exercise |
| **Apply / decline** | Apply: take the update. Decline: "not this version, not now". It does not close the template; a later version is a new offer. | Exercise (decline meaning: Design) |
| **Pending** | An engagement has an update waiting: the latest valid version is different from the one it is on, and the user has not declined it. Calculated each time, never stored. | Design |
| **Known / unknown** | Whether we are sure which version an engagement is on. Unknown engagements show "verifying", never "up to date". | Design |
| **Version pair** | The version an engagement is on and the version it would move to (e.g. `v5-CA → v7-CA`). We write one summary per version pair and language. | Design |
| **Summary** | A plain-language description of what changes between the two versions of a version pair. | Design |
| **Immutable record** | Saved once and never edited. A correction is saved as a new record. | Industry |
| **LLM** | The AI language model that rewrites the exact list of changes in plain language. | Industry |
| **Diff tool** | The existing tool that compares two versions of a template and lists what changed. | Exercise |
| **Template Store** | The existing database of all template versions, shared by all firms. | Exercise |
| **EMS** | Our name for the existing system that loads engagements, creates them and processes apply and decline. | Exercise (name: Design) |
| **Engagement DBs** | The customer-specific databases where engagement files are kept, in their region. | Exercise |
| **Load** | Opening an engagement in memory to read its template information. About 1 minute per file, a hard limit. | Exercise |
| **Event** | A message an existing system sends when something happens (publish, create, open, apply, decline). | Exercise |
| **Version Graph** | A small list of versions per product and market, with their status (published or withdrawn). | Design |
| **Pending Index** | For each engagement, only ids and versions: what it is on, what it declined, known or unknown. Built from events and can be rebuilt. | Design |
| **Query API** | What the UI calls to list engagements with their pending flag. | Design |
| **Backfill** | Filling the Pending Index for engagements that existed before it, by loading them at a controlled pace. | Industry |
| **Sampling check** | A small regular check that loads some engagements and compares them with the Pending Index, to find and fix mistakes. | Design |
| **Worker** | The program that makes the controlled calls to the EMS load (Part 2). | Design |
| **DLQ** | A separate queue for events or tasks that keep failing, with an alert so someone can fix them. | Industry |
| **Data residency** | Some firms must keep their data in their own region (EU, Canada). | Exercise |
