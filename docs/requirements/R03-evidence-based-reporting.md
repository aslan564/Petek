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
  `DELIVERY_UI`, `SITE_CHECK`, `INVESTIGATE`, `FLAKY`, `AGENT_FAILURE` or `INCONCLUSIVE`. A check whose evidence
  cannot decide it (`Verdict.INCONCLUSIVE`, Faza 24.12: a race nobody attempted or with one racer, unreadable racer
  requests, `stale_text`, a latency known only as a range around its limit, a check whose object no event carried,
  `id_unavailable`) is no evidence for A, B or C; a step whose
  only non-passing checks are such gets one `INCONCLUSIVE` finding on the "tool gap" shelf, never one about the site,
  and the run is not PASSED (the summaries count them as "inconclusive", JUnit as a property, SARIF as a note). A step that failed with a key becomes a
  finding of its own: a site defect a deterministic check saw (`unhealthy_page` from `site_health`,
  `access_not_refused` from `direct_url`, `forbidden_accepted` when the site accepted the request a forbidden-action
  step's `http_status` expects it to refuse, sent by the tester's own page during the action) is `SITE_CHECK` with
  UI/network evidence, a finding about the site told in the check's own words, never a tool gap; so is a race several
  racers won by the site's own answers (`several_winners`, 2026-09-30), while a race nobody contested is
  `INCONCLUSIVE` (`uncontested`); the summary also names what the run's scenario left unchecked (its `coverage:` lines,
  recorded at the run's start: roles the explorer never saw, ideas it could not write and why; 2026-09-30), so a passed
  run is never read as "everything was checked"; the report prints as a PDF when asked for (`report.pdf`: the
  panel's PDF button and `index.html`'s "PDF yüklə", `petek report --pdf`), from `share.html` by Chromium's own print
  (`ExportReportPdfUseCase`, `PlaywrightPdfPrinter`; 2026-09-30); a run is compared with an earlier run or release of
  its scenario (`petek compare`, the panel's Müqayisə, MCP `compare_runs`; `compare-<baseline run>.html` and `.md`, one pair per file): only what the
  site did is a change, a step a lost tester left undecided is not comparable, and speed is compared only where the
  site sets it (`RunComparer`, 2026-10-01): real-time delivery, the deterministic `run` steps and the pages' own timing
  (`site_health`'s `perf`, which the report's "Səhifə sürəti" shows per page and screen); how the pages look is
  compared too (ADR-0014, 2026-10-01): a look's frames are evidence (`VISUAL` artifacts and a `page_look` row,
  taken by code), the overlay and crops under `report/visual/<baseline>/` are derived from them, `visual.json` names
  every input by artifact id and verified sha256, and a changed look counts as worse only with the owner's
  `--visual fail` gate; a finding without
  evidence of its own links its step's last screenshot, which `site_health`
  takes on the first page that went wrong; the plain summary names a problem several testers saw once, with how
  many saw it (2026-09-27). A finding whose step has no check of the sender's own shows as A what the sender did,
  from its own record (`do: <task> -> PASSED: <summary>`), never a blank; that record only shows, the checks alone
  classify. A FAILED check of any source keeps the actor's page next to its own evidence (the target's answer, the
  request), and a FAILED group check (a race) keeps every racer's page, so each failure has a screenshot
  (`RecordingVerifyStepUseCase`, `ContractDemoEndToEndTest`).
- **Expected outcomes.** `ExpectedOutcomes` is the one place that decides which failing-looking records the test
  expected: a lost race (`lost_race`) and an expected refusal (`permission_denied`), each together with the agent's
  own records of that action (same correlation id); and a recovered turn, one tool call of an action whose own record
  (`do: …`, `run …`) PASSED, such as a `select` whose error listed the options the agent then chose from, or a retried
  attempt of a run function, so findings, the summary and stability never count a step the action completed as failed. Which step is a permission test is decided by code in the
  orchestrator from the step's assertions (`not_visible`, `http_status` 401/403, ADR-0007), never from the wording
  of the agent's report; the report shows such rows as "icazə verilmədi" and counts them as passed. The requests the
  tester's page sent during such an action are read too: one the site accepted on the method and path of such an
  `http_status` is `forbidden_accepted`, whatever the agent said (Faza 24.2).
- **Report.** `BuildReportUseCase` → `ReportModel` → `HtmlReportWriter` / `MarkdownReportWriter`: summary, steps with
  screenshots, assertions, latency stats (avg/p95/max per receiver), findings with A/B/C, failed agents, stability,
  usage (tokens, cost per agent). Reports live next to the evidence (`<runDir>/report/`).
- **Stability.** `run --repeat N` groups runs; the stability analysis reports pass rate and flaky steps. Each run
  that did not pass a step is put on whom it failed on (Faza 24.13, `FailureKeys.causeOf`): the site (a failed check,
  a defect code saw, no e-mail sent, a racer's request turned down), the surroundings (inbox, shared IP, AI provider,
  browser, a wave that could not check it) or the tester's agent (a loop, the step limit, a time-out, and what follows
  from it); an actor whose own action broke leaves its failed checks moot. Only a step the site fails at times is
  `flaky`; one that varies because of agents, surroundings or runs that did not check it is "qeyri-sabit ... (saytın
  xətası deyil)" (`unsteady`), in the report and the panel.

## Modules and key types

`evidence`: `EvidenceRecorder`, `EvidenceQuery`, `RunRepository`, `ArtifactStore`, `FindingRecord`, `UsageRecord`.
`reporting`: `Judge`, `ThreeSourceJudge`, `ReportModel`, `ReportWriter`, `StabilityAnalysis`. `orchestration`:
`RunFinalizer`, `FinalizeRunUseCase`. `app`: `UsageFlushingFinalizer`.

## Verification

- `evidence`: SQLite store tests (unique constraints, single writer), `InMemoryEvidence` fixture for others.
- `reporting`: judge classification tests, report writer tests (HTML sections, Markdown), stability tests.
- `app`: `ReportCommandTest`, `UsageFlushingFinalizerTest`.

## Open items

- The report's "steps passed" counts every recorded action (each agent turn and flow sub-action: 315 for a 10-tester
  demo run), the CLI summary counts the orchestrator's tasks (33). Both are correct and both are called steps; one
  name for each, and the table grouped by task, when the panel and the report are reworked (Faza 12).
- Evidence tiers (`ORACLE_CONFIRMED`, `UI_NETWORK`, `LLM_JUDGED`) on every finding — Faza 10 (ADR-0010).
- Single-file HTML with inline screenshots and PDF export for sharing — Faza 12.
- Regression baselines between releases — Faza 14.
