# R05 — Real-time delivery measured in the receiver's DOM, transport detected, never assumed

**Status:** Implemented · **Plan:** Faza 4 · **ADRs:** 0006

## Requirement

When one tester publishes something (an announcement, a ticket status), Pətək measures how long it takes to appear
for every other tester, per receiver, and reports the distribution and the receivers it never reached. The owner
decided that the target's real-time mechanism (WebSocket, SSE, polling) is not an input: Pətək must not depend on it.

## Why

"The announcement reached 28 of 29 in under 2 s, one never" is the finding that matters; it cannot be produced by
looking at the server alone or by one browser.

## Architecture

- **Event bus.** A step with `emits: <event>` publishes the event on the in-process `EventBus` with t0 from the harness
  clock the moment the action completed; `wait_for: <event>` in the receivers' steps blocks until it arrives (with a
  timeout), then each receiver's assertion (`visible_text` …) measures t1 when the text is visible in its own DOM.
- **Latency.** `latency_max` asserts per receiver; the report shows avg/p95/max and the missing receivers.
- **Transport detection.** `features/browser` watches network traffic of each session and records the transport
  (WebSocket, SSE, polling) as an observation shown in the report — information, not a dependency.
- **Receipts.** Where the target has read receipts (`is_read`), the `receipts` oracle assertion compares them with the
  DOM (source C of the three-source rule).

## Modules and key types

`orchestration`: `EventBus`, scheduler `wait_for` handling, task events. `browser`: realtime transport detection,
`BrowserSession.waitForText`. `verification`: `latency_max`, receipt assertions. `reporting`: latency statistics.

## Verification

- `orchestration`: bus and scheduler tests (t0 stamping, timeouts, emitter failure handling).
- `verification`: latency assertions with `FakeHarnessClock`.
- `e2e`: announcement scenario on the fake target (SSE) — receivers measure independently, no serialisation.

## Open items

- None for the MVP; regression baselines of latency across releases are Faza 14.
