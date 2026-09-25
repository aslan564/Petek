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

## Isolation guarantees (audited 2026-09-25)

Nobody but the orchestrator sees more than one tester. What holds, and where the code enforces it:

| Guarantee | Enforced by |
|---|---|
| One browser context, one page, one Playwright driver and one thread per session; nothing enumerates contexts | `PlaywrightHandles.create`, `ConfinedThread`, `PlaywrightBrowserSession.perform` |
| Every session is created by the orchestrator with `label = agentId`; runtime, session and variables are 1:1 per identity | `DefaultCampaignRunner.openAgent` |
| An agent's runtime knows its colleagues as `Colleague`s (name, role, e-mail, department, registration) — never their password or phone | `AgentRuntime.roster`, `Colleague.of` |
| An agent can type only its own credentials; placeholders resolve `self.*`, `vars.*` and the shared company code only | `PlaceholderResolver`, `FlowTemplates` |
| Passwords never reach a prompt or evidence: own-password redaction plus adapter-side masking of secret fields | `SecretRedaction`, `PlaywrightBrowserSession` snapshots |
| Shared run values (`company_id`, `company_code`, invite links) are write-once: the first publisher wins, a different later value is refused | `SharedRunState.put`, `InMemorySharedRunState` |
| Only the admin may run `register_owner` and `seed_company`; every event name has exactly one emitting step | `DefaultCampaignValidator` |
| `{last_id}` is the actor's own emitted object, the object it waited for, or the state before the step began — never a colleague's id from the same step | `StepExecutor.lastIdBeforeStep` |
| Evidence `agent_id`, event emitters and receipts come from harness state, never from the model | `StepEvidence`, `HarnessEvidence`, `StepExecutor.emit` |
| Evidence writes are serialized (one SQLite writer thread, atomic artifact files) | `SqliteDatabase`, `FileSystemArtifactStore` |
| Saved storage states (live cookies) are `rw-------` in a `rwx------` directory, one file per (run, agent) | `PlaywrightBrowserSession.saveStorageState` |
| Mail and OTP are looked up by the agent's own e-mail and phone only | `DefaultAgentLoop`, `FlowTemplates` |

Known trade-off: in `SHARED_SERVER` topology up to 20 contexts share one Chromium process tree (shared fate and CPU
contention, never shared state); `PER_SESSION` gives every tester its own browser at a higher memory cost.

## Verification

- **`TesterIsolationAtScaleTest`** (`features/orchestration`, runs with `build`): the real orchestrator, step executor,
  event bus and shared state drive **100, 1 000 and 5 000** testers (fake browser and scripted decisions) through a
  KadroHR-shaped campaign and assert every guarantee above that the harness owns: distinct identity, session object,
  runtime and storage path per tester; every agent acted as itself; 4 999 attempts to change the admin's company code
  refused; every step, wait, event and receipt attributed to the right agent; `{last_id}` never a colleague's id.
  Measured 2026-09-25: 100 testers 0.15 s, 1 000 testers 1.5 s, 5 000 testers 13 s (virtual time).
- **`BrowserIsolationAtScaleTest`** (`features/browser`, runs with `build`, `-Dpetek.isolation.sessions=N`): N real
  Chromium contexts on shared servers log in as different users at once; every session — all concurrently, twice —
  is checked for its own cookie (`/api/me`), page text, localStorage and thread; saved storage states hold only the own
  cookie. Measured 2026-09-25 on a 4-core / 16 GB container: **30 sessions** pass in 20 s (2 servers), **60 sessions**
  pass in 22 s (3 servers, ≈8.1 GB, ≈135 MiB per session including its Node driver); **100 sessions** open in 29 s on
  5 servers but saturate that machine (14.2 GB, load 295) and were aborted — the limit of the machine, not of the
  isolation. Bigger proofs need more memory, or the driver-sharing planned below.
- `PlaywrightBrowserEngineTest` (ten sessions on one server, sharding, process-tree shutdown),
  `InMemorySharedRunStateTest` (5 000 concurrent publishers, one winner), `DefaultCampaignValidatorTest` (admin-only
  run functions, one emitting step per event), `DefaultCampaignRunnerTest`, `FlowRunnerTest`, `InactivityWatchdogTest`.
- `e2e`: end-to-end runs against the fake target with real Chromium (`:e2e:e2eTest`).

## Open items

- Scale cost: every session starts its own Playwright driver (Node process); sharing one driver per browser server
  would cut memory per session substantially (Faza 14, together with `petek capacity` measurements).
- Passwords are stored in clear in the identity table although they are derivable from the identity secret; store
  nothing and re-derive (Faza 10).
- The usage meter and the watchdog are keyed by agent id, not run id; harmless while the panel runs one campaign at a
  time, to be scoped per run before parallel runs (Faza 14).
- Multi-machine orchestration (Redis/NATS) is a paid-edition port (Faza 14, ADR-0011).
