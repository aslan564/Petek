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
- **Resilience.** An inactivity watchdog marks a stuck agent `blocked` and lets the others continue; waiting in the
  shared AI queue or for the provider's answer is never inactivity (2026-10-01), so the number of testers never blocks
  one; a crashed context is recreated from the same identity and the `storage_state` saved since (the restore record
  says whether one was loaded); `on_fail: continue | abort` is the campaign's choice.
- **Nobody left out (2026-10-01).** Every run records its roster and, at its end, a `not_reached` record for every
  planned tester × step without a result (why: aborted, wave not started, failed earlier, never reached) and an
  `uncovered` record (FAILED) for a step nobody ran (not for one a wave the run stopped before would have run: its
  testers get `not_reached`); the report's roll call shows planned against acted. `petek run`
  and `petek plan` name every step that would start with nobody, wave by wave, before the run; the validator follows
  how departments are dealt; `{last_id}` of a step many testers emit with a source that does not name the tester is
  warned about.
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
| `{last_id}` is the step's own object only: the one the actor waited for or, in its checks, emitted — never a colleague's id from the same step nor another step's object; no id is a template error (Faza 24.6) | `StepExecutor.runActor`, `DefaultCampaignValidator` |
| `{pass}` is `<run tag>-<n>` of the execution the step's own event belongs to (the first pass, a later wave, the account swap), so a text published again is new every time and in every run | `RunState.passMark`, `StepExecutor.passOf`, `Placeholder.Pass` |
| Evidence `agent_id`, event emitters and receipts come from harness state, never from the model | `StepEvidence`, `HarnessEvidence`, `StepExecutor.emit` |
| Evidence writes are serialized (one SQLite writer thread, atomic artifact files) | `SqliteDatabase`, `FileSystemArtifactStore` |
| Saved storage states (live cookies, localStorage and the tabs' sessionStorage) are `rw-------` in a `rwx------` directory, one file per (run, agent) | `PlaywrightBrowserSession.saveStorageState`, `SessionStorageState` |
| Mail and OTP are looked up by the agent's own e-mail and phone only | `DefaultAgentLoop`, `FlowTemplates` |

Known trade-off: in `SHARED_SERVER` topology up to 20 contexts share one Chromium process tree (shared fate and CPU
contention, never shared state); `PER_SESSION` gives every tester its own browser at a higher memory cost.

## Verification

- **`TesterIsolationAtScaleTest`** (`features/orchestration`; 100 and 1 000 testers on every `build`, 5 000 in CI's
  e2e job via `-Dpetek.isolation.testers=5000`): the real orchestrator, step executor,
  event bus and shared state drive **100, 1 000 and 5 000** testers (fake browser and scripted decisions) through a
  portal-shaped campaign and assert every guarantee above that the harness owns: distinct identity, session object,
  runtime and storage path per tester; every agent acted as itself; 4 999 attempts to change the admin's company code
  refused; every step, wait, event and receipt attributed to the right agent; `{last_id}` never a colleague's id.
  Measured 2026-09-25: 100 testers 0.15 s, 1 000 testers 1.5 s, 5 000 testers 13 s (virtual time).
- **`BrowserIsolationAtScaleTest`** (`features/browser`, tagged `e2e`: `./gradlew :features:browser:isolationTest
  -Dpetek.isolation.sessions=N`, part of the root `e2eTest`, run by CI's e2e job with the default 30): N real
  Chromium contexts on shared servers log in as different users at once; every session — all concurrently, twice —
  is checked for its own cookie (`/api/me`), page text, localStorage and thread; saved storage states hold only the own
  cookie. Measured 2026-09-25 on a 4-core / 16 GB container: **30 sessions** pass in 20 s (2 servers), **60 sessions**
  pass in 22 s (3 servers, ≈8.1 GB, ≈135 MiB per session including its Node driver); **100 sessions** open in 29 s on
  5 servers but saturate that machine (14.2 GB, load 295) and were aborted — the limit of the machine, not of the
  isolation. Bigger proofs need more memory, or the driver-sharing planned below.
- `PlaywrightBrowserEngineTest` (ten sessions on one server, sharding, process-tree shutdown),
  `InMemorySharedRunStateTest` (5 000 concurrent publishers, one winner), `DefaultCampaignValidatorTest` (admin-only
  run functions, one emitting step per event), `DefaultCampaignRunnerTest`, `FlowRunnerTest`, `InactivityWatchdogTest`.
- **`ScaleProofEndToEndTest`** (`app`, tagged `scale`: `./gradlew :app:scaleTest -Ppetek.scale.testers=30,50,100`,
  never part of `build` or `e2eTest`): the repository's contract demo with `--testers N` in real Chromium, the
  production object graph and a deterministic AI against the fake target. For every size: exactly N testers, each with
  its own name, e-mail and phone, all past their gate; the roster lists all N; no `not_reached`, `uncovered` or `abort`
  record; the scenario's actors resolved again over the run's testers and every tester × step pair has that tester's
  own record; every joiner's session shows its own name; every check passed and every employee received the
  announcement; the report says "Planlanan: N · İşləyən: N · Bütün addımlarını bitirən: N". With the site dropping the
  announcement for exactly one employee, that tester's finding is the only one, whatever N is.
- `RunnerLlmQueueTest` (100 `do` actors, 6 AI slots, 15 s per decision, virtual time: nobody blocked),
  `RunnerRollCallTest`, `RunnerUnfinishedActionTest`, `ImapMailboxTest` and `MailpitMailboxTest` (100 testers waiting
  at once), `BuildReportUseCaseTest` (the roll call in the report).
- `./gradlew e2eTest` (root): the panel end to end against the fake target in real Chromium (`:app:e2eTest`,
  `PanelEndToEndTest`), the `e2e` module's browser runs (`:e2e:e2eTest`) and the isolation proof above. Kover keeps
  these out of `build`, so the fast build never opens a browser and the live suite never spends LLM quota.

## Open items

- Scale cost: every session starts its own Playwright driver (Node process); sharing one driver per browser server
  would cut memory per session substantially (Faza 14, together with `petek capacity` measurements).
- The generated testers' passwords are stored in clear in the identity table although they are derivable from the
  identity secret; store nothing and re-derive (Faza 10). The owner's accounts' passwords (`login` testers) are no
  longer stored there at all (2026-10-02, R04).
- The usage meter and the watchdog are keyed by agent id, not run id; harmless while the panel runs one campaign at a
  time, to be scoped per run before parallel runs (Faza 14).
- Multi-machine orchestration (Redis/NATS) is a paid-edition port (Faza 14, ADR-0011).
