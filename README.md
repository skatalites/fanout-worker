# Template Update: Design and Worker

A design for showing users which engagement files have a pending template update, and a Java worker that makes the slow calls safely (Part 2).

## Contents

- [`docs/design-document.md`](docs/design-document.md): the design (Part 1).
- [`docs/glossary.md`](docs/glossary.md): the terms used in the design.
- [`docs/state-catalog.md`](docs/state-catalog.md): every state used, and whether it is saved or calculated (optional reading).
- [`docs/architecture.drawio`](docs/architecture.drawio): the architecture diagram. Open it at app.diagrams.net.
- [`docs/AI-collaboration-log.md`](docs/AI-collaboration-log.md): how AI was used.
- [`fanout-worker/`](fanout-worker/): the Part 2 worker (Java 21, Maven). It has its own README with the trade-offs.

## Run the worker tests

You need Java 21 and Maven.

```
cd fanout-worker
mvn test
```
