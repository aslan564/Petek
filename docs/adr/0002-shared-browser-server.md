# ADR-0002: One shared Chromium server, one confined Playwright per agent

**Status:** Accepted, amended 2026-09-25 (see "Amendment" below: any number of agents, sharded servers, dialogs)
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Thirty agents act at the same time, each needing a fully isolated session (cookies, localStorage, sessionStorage).
Playwright Java is not thread-safe: an instance must only be used from the thread that created it. Memory must stay
reasonable on one machine. docs/PLAN.md names the pattern as the first technical risk (Faza 3).

## Decision
`PlaywrightBrowserEngine` starts one Chromium as a Playwright **browser server** (`launchServer` run by the Node.js
driver bundled in the Playwright jar, bound to 127.0.0.1 with a random path). Every `BrowserSession` owns a
single-thread dispatcher. It creates its own `Playwright` instance on that thread, connects with
`BrowserType.connect(ws)`, and opens its own browser context. Every Playwright call goes through that dispatcher.
`PER_SESSION` (one Chromium per agent) remains as a configurable fallback.

## Options Considered

### Option A: One Chromium per agent
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | High memory/CPU (≈30 browser processes) |
| Scalability | Poor beyond ~30 agents |
| Team familiarity | High |

**Pros:** simplest, full isolation. **Cons:** heavy; startup time multiplied.

### Option B: One browser, `connectOverCDP` from each agent
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | Low |
| Scalability | Good |
| Team familiarity | Medium |

**Pros:** no extra Node process. **Cons:** Playwright documents CDP connections as lower fidelity; a Playwright instance must stay alive to keep Chromium running.

### Option C (chosen): Browser server + `connect(ws)` + per-session thread confinement
| Dimension | Assessment |
|---|---|
| Complexity | Medium–High |
| Cost | Low (one Chromium, 30 contexts) |
| Scalability | Good on one machine; the same protocol works across machines later |
| Team familiarity | Medium |

**Pros:** full Playwright protocol, the server outlives any session, the server cleans up a lost connection's contexts, client and server versions always match.
**Cons:** depends on Playwright's `impl.driver` package to locate Node.js (an upgrade can break it), one extra process, one WebSocket hop per call.

## Trade-off Analysis
Option C keeps Option B's memory profile with Option A's fidelity. The internal-API dependency is the price. It is contained in
one class (`PlaywrightDriver`) and covered by tests that start the real server, so a Playwright upgrade fails loudly.

## Consequences
- Easier: 30 agents in one Chromium; a crashed agent does not kill the browser.
- Harder: a cancelled call keeps its session thread busy until Playwright's own timeout; `stop()` has a grace period.
- Revisit: Windows argument quoting for the server launch is untested; dialogs (`confirm()`) are auto-dismissed by Playwright. Decide whether to auto-accept and record them.

## Action Items
1. [x] Engine, confined sessions, SSE/WebSocket/polling detection, tests with 10 concurrent sessions.
2. [x] Measure memory/CPU per session and per browser (`petek capacity --measure`, see the amendment).
3. [x] Decide the dialog policy (accept and record vs dismiss): accept and record.
4. [ ] Measure a large run (hundreds of agents) against the fake target in the e2e suite.

## Amendment (2026-09-25): no fixed agent count, sharding across browser servers, capacity advice, dialogs

**Context.** The owner's requirement: thirty agents are not a rule. How many testers run depends on the machine; a
strong one may run 100 or 500. Nothing may forbid that; the user gets a *recommendation* of the maximum instead.
One Chromium carrying hundreds of contexts would be a single point of failure and hard to reason about.

**Decision.**
- *No fixed agent count.* Agent ids grow without a bound (`a01`..`a99`, `a100`, `a1000`, ...) and compare by number;
  the identity registry names any number of testers; the live board fits the terminal.
- *Sharding.* One browser server hosts at most `contextsPerBrowser` sessions (`BrowserEngineConfig`, default 20). The
  first server starts with the engine; the next starts lazily when every running server is full; a new session goes to
  the least-loaded server with room; closing a session frees its slot; a dead server is skipped; `stop()` stops every
  server. At most one session per core (4..16) opens at the same moment, so a large run starts without overloading the
  machine. `PER_SESSION` is unchanged.
- *Capacity advice.* `features/capacity` (`petek capacity`) recommends the maximum testers from free memory (minus a
  reserve of `max(2 GiB, 15 %)`), the per-session and per-browser cost (estimated, or measured with real sessions) and
  6 sessions per core. It never enforces the number; `run` only warns.
- *Dialogs.* JavaScript dialogs are accepted as they open (OK / leave page; a prompt gets its default text) and recorded
  (`BrowserSession.drainDialogs()`: type, message with typed secrets masked, harness time). The agent loop and the run
  functions put them into the step detail and into what the model sees next, instead of Playwright dismissing them
  silently.

**Consequences.**
- Easier: any number of agents, 20 per Chromium by default; a crashed or overloaded browser affects only its shard;
  memory grows in steps of one browser per `contextsPerBrowser` agents, which is what the capacity advice counts.
- Easier: a `confirm()` no longer turns into a silent "no"; the report shows every dialog.
- Harder: one more Node.js host per shard; accepting every dialog also confirms destructive actions an agent triggers
  (on test tenants only, per ADR-0007).
