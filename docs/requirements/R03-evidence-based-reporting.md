# R03 — Evidence-based reporting: ids everywhere, three-source judge, stability

**Status:** Implemented · **Plan:** Faza 5 · **ADRs:** 0005, 0007

## Requirement

No verdict without evidence: every step is recorded, every pass/fail is tied to at least one screenshot, DOM read,
network exchange or oracle answer. Everything has an id (`run_id`, `agent_id`, `step_id`, `event_id`,
`correlation_id`). Findings show what the sender did (A), what the receiver saw (B) and what the target's test API
says (C). Repeating a run measures stability and flags flaky steps.

## Why

A developer must be able to act on a report without re-running anything, and to trust that a "failed" is the target's
fault, not the tool's. Evidence is also the product's sales material (Faza 12).

## Architecture

- **Store.** `features/evidence` writes runs, identities, steps, events, receipts, artifacts, assertions, findings and
  usage to one SQLite database (single writer, WAL) plus artifact files; `core/sqlite` owns the connection.
- **Ids.** `core/domain` generates UUIDv7-based ids with prefixes (`run_`, `scn_`, …); agent ids are `a01…` without an
  upper bound (rule 4). `workspace_id` joins them in Faza 8 (ADR-0011).
- **Judge.** `features/reporting` `ThreeSourceJudge` folds A/B/C into `FindingRecord`s classified `BACKEND`,
  `DELIVERY_UI`, `INVESTIGATE`, `FLAKY` or `AGENT_FAILURE`.
- **Report.** `BuildReportUseCase` → `ReportModel` → `HtmlReportWriter` / `MarkdownReportWriter`: summary, steps with
  screenshots, assertions, latency stats (avg/p95/max per receiver), findings with A/B/C, failed agents, stability,
  usage (tokens, cost per agent). Reports live next to the evidence (`<runDir>/report/`).
- **Stability.** `run --repeat N` groups runs; the stability analysis reports pass rate and flaky steps.

## Modules and key types

`evidence`: `EvidenceRecorder`, `EvidenceQuery`, `RunRepository`, `ArtifactStore`, `FindingRecord`, `UsageRecord`.
`reporting`: `Judge`, `ThreeSourceJudge`, `ReportModel`, `ReportWriter`, `StabilityAnalysis`. `orchestration`:
`RunFinalizer`, `FinalizeRunUseCase`. `app`: `UsageFlushingFinalizer`.

## Verification

- `evidence`: SQLite store tests (unique constraints, single writer), `InMemoryEvidence` fixture for others.
- `reporting`: judge classification tests, report writer tests (HTML sections, Markdown), stability tests.
- `app`: `ReportCommandTest`, `UsageFlushingFinalizerTest`.

## Open items

- Evidence tiers (`ORACLE_CONFIRMED`, `UI_NETWORK`, `LLM_JUDGED`) on every finding — Faza 10 (ADR-0010).
- Single-file HTML with inline screenshots and PDF export for sharing — Faza 12.
- Regression baselines between releases — Faza 14.
