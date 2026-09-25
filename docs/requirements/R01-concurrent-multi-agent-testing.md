# R01 — Many AI testers at once, each in an isolated browser session

**Status:** Implemented · **Plan:** Faza 2–3 · **ADRs:** 0001, 0002, 0006

## Requirement

N AI tester agents (30 in the first campaign, any number later) act on the target at the same time, each as a distinct
user with its own account and browser state, coordinated by an orchestrator so that multi-user scenarios (one sends,
many receive; two act on the same object; roles differ) can be played out and observed.

## Why

Multi-user and real-time defects only appear under concurrent use by distinct sessions. One tester with one browser
cannot produce them; thirty humans with thirty devices cannot repeat them every release.

## Architecture

- **One JVM, N coroutines.** `features/orchestration` runs every agent as a coroutine; a scheduler resolves actor
  expressions (`admin`, `employee[*] | manager[*]`, `employee[1..3]`) to agents and runs a step's actors in parallel,
  with `campaign.pacing` staggering starts to respect per-IP limits.
- **Isolated browsers.** `features/browser` starts Chromium browser servers (sharded by load, `contextsPerBrowser`) and
  gives every agent one browser context. Every `BrowserSession` owns a Playwright instance confined to its own
  single-thread dispatcher (rule 9), so sessions never share Playwright objects.
- **Distinct identities.** `features/identity` derives a deterministic registry per run; each agent reads its own
  identity only (rule 7) and proves isolation by reading its own name after login.
- **Resilience.** An inactivity watchdog marks a stuck agent `blocked` and lets the others continue; a crashed context
  is recreated from the same identity and saved `storage_state`; `on_fail: continue | abort` is the campaign's choice.
- **No fixed limit.** `features/capacity` recommends a maximum for the machine; `run` warns above it and still starts.

## Modules and key types

`orchestration`: `CampaignRunner`, `DefaultCampaignRunner`, `ActorResolver`, `EventBus`, `InactivityWatchdog`,
`MonitorView`. `browser`: `BrowserEngine`, `BrowserSessionFactory`, `BrowserSession`, `PlaywrightBrowserEngine`,
`BrowserTopology`. `identity`: `IdentityRegistryGenerator`, `IdentityRepository`. `capacity`: `CapacityAdvisor`.

## Verification

- `features/browser`: `PlaywrightBrowserEngineTest` (ten concurrent sessions share one server and stay isolated;
  sharding; shutdown kills the process tree) — real Chromium.
- `features/orchestration`: scheduler, actor resolution, watchdog and runner tests with `FakeBrowserSession`.
- `e2e`: end-to-end runs against the fake target with real Chromium (`:e2e:e2eTest`).
- Success criteria in `docs/PLAN.md`: 30 agents log in at once and each reads its own name; one stuck agent does not
  stop the rest.

## Open items

- Multi-machine orchestration (Redis/NATS) is a paid-edition port (Faza 14, ADR-0011).
