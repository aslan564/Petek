# ADR-0002: One shared Chromium server, one confined Playwright per agent

**Status:** Accepted
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
2. [ ] Measure memory/CPU for 30 agents in the e2e run (PLAN Faza 3 exit criterion).
3. [ ] Decide the dialog policy (accept and record vs dismiss).
