# ADR-0005: SQLite evidence store with a single writer plus artifact files

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Every step, event, receipt, assertion and finding must be persisted with ids (rules 4 and 5). Runs must be comparable
(`--repeat`), and 30 agents write concurrently. The MVP runs on one machine with no infrastructure to operate.

## Decision
Persist to one SQLite file (Exposed 1.x over sqlite-jdbc) in WAL mode. All writes are serialized on one dedicated
writer thread; reads run on `Dispatchers.IO`. Evidence files (screenshots, a11y trees, oracle bodies) are written
atomically under `<evidence>/<run_id>/<agent>/<seq>-<type>.<ext>` with a SHA-256 hash, referenced from the database.

## Options Considered

### Option A: PostgreSQL
| Dimension | Assessment |
|---|---|
| Complexity | Medium (a service to run) |
| Cost | Operational overhead |
| Scalability | High, multi-machine |
| Team familiarity | High |

**Pros:** concurrency, multi-machine (Faza 8). **Cons:** infrastructure before it is needed.

### Option B: JSON/NDJSON files per run
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | None |
| Scalability | Poor queries, no constraints |
| Team familiarity | High |

**Pros:** trivial. **Cons:** no uniqueness guarantees (e-mail/name), hard cross-run analysis.

### Option C (chosen): SQLite + single writer + file artifacts
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | None |
| Scalability | Fine for one machine and hundreds of runs |
| Team familiarity | High |

**Pros:** zero ops, constraints (UNIQUE email / run+name), fast local queries, easy to ship with a report.
**Cons:** one writer thread caps write throughput (sufficient for 30 agents); not shared across machines.

## Consequences
- Easier: `report`/`teardown` for any past run; stability across `--repeat` groups.
- Harder: multi-machine orchestration (Faza 8) needs a server database — the repositories are ports, so it is an adapter swap.
- Revisit: Exposed's DEBUG SQL logging prints bound values (test passwords). Keep that logger at WARN.
