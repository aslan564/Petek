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

- **Event bus.** A step with `emits: <event>` publishes the event on the in-process `EventBus` once the action
  completed; `wait_for: <event>` in the receivers' steps blocks until it arrives (with a timeout), then each receiver's
  assertion (`visible_text` …) checks its own DOM. A
  waiter takes only an event of the latest execution of the step that emits it (the bus sequence at that step's start
  is its cursor), so the account swap, which runs the main steps again on the same bus, never hands a receiver the
  first pass's event; a setup event keeps serving, since setup runs once (Faza 24.4). With `campaign.wave_size` a
  tester whose role nobody else has is live in every wave, so an announcement of the company's owner is made and read
  in every wave, and the events of setup steps are carried to every later wave's bus (Faza 24.11); a receiver whose
  wave still has no tester of the emitting step is skipped (`emitter_absent`) rather than failed after its timeout;
  the run reports per step how many receivers could wait, and one no receiver could wait for anywhere is
  `not_covered`, a failure (Faza 24.7). In setup such a tester cannot get ready: it is left out of the later steps
  with `emitter_absent`, never counted as set up.
- **Latency from the write (Faza 24.10).** t0 is when the change reached the target, not when the emitter's agent
  finished talking about it: the answer to the emitter's own request, as its page saw it (`emits: {event, request:
  "POST /api/announcements"}`; without `request` the action's first accepted mutating request, an upper bound when it
  sent several). The publish time is kept apart (`published_at` in the event's payload); when the page showed no write
  (another origin's API, a WebSocket message) t0 is the publish and the delay is only known to lie between it and the
  start of the action. t1 is when the text appeared on the receiver's page: as the emitting step begins, the receivers
  of its event start watching their own pages for the text of their `visible_text` (`BrowserSession.watchText`; the
  page times the text itself, on DOM changes and every 50 ms), so neither the emitter's agent working on after the
  write nor the steps in between are counted or hidden. A text already on the page when the watch began proves nothing
  (`stale_text`: the same text published again in a later wave or the account swap, or text the page always shows). A
  text naming the event's own object (`{last_id}`) cannot be watched for before the object exists; it is checked after
  the event, and a text visible at the first look gives only an upper bound.
- **Latency.** `latency_max` asserts per receiver on the range the delay lies in: PASSED when even its longest is
  within the limit, FAILED when even its shortest exceeds it, and INCONCLUSIVE in between (Faza 24.12); a
  `stale_text` is INCONCLUSIVE too, never a delivery defect of the site. The
  report shows avg/p95/max and the missing receivers.
- **Transport detection.** `features/browser` watches network traffic of each session and records the transport
  (WebSocket, SSE, polling) as an observation shown in the report — information, not a dependency.
- **Receipts.** Where the target has read receipts (`is_read`), the `receipts` oracle assertion compares them with the
  DOM (source C of the three-source rule).

## Modules and key types

`orchestration`: `EventBus`, `EventOrigin`/`EventWrite` (the write behind an event), receivers watching ahead
(`StepExecutor.armReceivers`), scheduler `wait_for` handling, task events. `browser`: realtime transport detection,
`BrowserSession.waitForText`, `watchText`/`stopTextWatch` (`text-watch.js`). `verification`: `EventTime`,
`WatchedText`, `latency_max`, receipt assertions. `reporting`: latency statistics.

## Verification

- `orchestration`: bus and scheduler tests (t0 stamping, timeouts, emitter failure handling); `RunnerDeliveryTest`: a
  slow delivery hidden behind the emitter's long answer fails `latency_max`, the step's request is the write, a text
  already on the page and the swap's second publication are `stale_text`, every watch ends.
- `browser`: real-Chromium watches of a text appended, revealed by a style change and inside a shadow root.
- `verification`: latency assertions with `FakeHarnessClock`; `WatchedDeliveryTest` for watches and latency ranges.
- `e2e`: announcement scenario on the fake target (SSE) — receivers measure independently, no serialisation.

## Open items

- None for the MVP; regression baselines of latency across releases are Faza 14.
