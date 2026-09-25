# Pətək architecture

Pətək is one JVM process. An orchestrator runs N tester agents as coroutines. Each agent owns one isolated browser
context on a shared Chromium and writes evidence to one SQLite database. The code is split by **feature**. Every
feature follows **clean architecture** (domain → application → infrastructure), and all wiring happens in `app/`
(the composition root).

## Modules

| Module | Responsibility | Key ports (domain) | Infrastructure |
|---|---|---|---|
| `core/domain` | Shared kernel: ids, harness clock, `Secret`, `TargetPolicy`, `Role`, `RegistrationMode` | `IdGenerator`, `HarnessClock` | UUIDv7 ids, system clock |
| `core/sqlite` | One SQLite database per evidence dir (WAL, busy timeout, single writer) | — | Exposed 1.x JDBC |
| `features/campaign` | Campaign/scenario model, actor grammar, templates, validation | `CampaignSource`, `ActorExpressionParser`, `TemplateRenderer`, `CampaignValidator` | kaml YAML reader with line numbers |
| `features/identity` | Deterministic identity registry (names, e-mail, password, phone, role, department, registration mode) | `IdentityRegistryGenerator`, `PasswordDeriver`, `IdentityRepository` | SQLite repository |
| `features/evidence` | Runs, steps, events, receipts, artifacts, assertions, findings, usage | `EvidenceRecorder`, `EvidenceQuery`, `RunRepository`, `ArtifactStore` | SQLite + file system |
| `features/mail` | Verification codes and invitation links from the test inbox | `Mailbox`, `VerificationExtractor`, `AwaitVerificationUseCase` | Mailpit REST client (Ktor) |
| `features/oracle` | Target test API (source of truth "C") | `TargetOracle`, `JsonFieldSelector` | Ktor client with `X-Test-Token`, `is_test` guard |
| `features/browser` | Isolated browser sessions, snapshots for the LLM, real-time transport detection | `BrowserEngine`, `BrowserSessionFactory`, `BrowserSession` | Playwright (shared browser server, thread-confined sessions) |
| `features/llm` | Structured-output LLM calls, retries, concurrency limit, usage metering | `LlmClient` | Claude Code CLI (`claude -p`, Claude plan) and Anthropic Java SDK |
| `features/agent` | Tool whitelist, decision protocol, agent loop, deterministic `run` functions | `DecisionProtocol`, `LoopDetector`, `AgentLoop`, `RunFunction`, `TesterAgent` | — |
| `features/verification` | Typed assertions (visible_text, not_visible, oracle, http_status, count, latency_max, only_one_succeeds) | `AssertionEvaluator`, `VerifyStepUseCase` | — |
| `features/orchestration` | Run lifecycle, actor resolution, event bus, scheduler, watchdog, teardown, repeat, live board | `EventBus`, `ActorResolver`, `MonitorView`, `CampaignRunner`, `RunFinalizer` | in-process bus, Mordant board |
| `features/reporting` | Three-source judge, stability analysis, Markdown + HTML report | `Judge`, `ReportWriter` | kotlinx.html |
| `features/explorer` | Explorer agent (PLAN.md Faza 6–7): learns a site model, records findings, generates campaign drafts, diffs model versions | `ExplorationRepository`, `ExplorationObserver`, `TestTargetCheck` | SQLite repository |
| `app` | CLI (`plan`, `run`, `report`, `teardown`, `smoke`, `doctor`), `.env` config, composition root, logging | — | Clikt, logback |
| `testing/fake-target` | A small KadroHR-like site + Mailpit-compatible API + test API, implementing `docs/TARGET_CONTRACT.md` | — | Ktor server + SSE |
| `e2e` | Architecture rules (Konsist) and end-to-end runs against the fake target with real Chromium | — | — |

## Dependency rules

```mermaid
flowchart TD
  app --> orchestration & reporting & llm & mail & oracle & browser & identity & evidence & campaign & sqlite[core/sqlite]
  orchestration --> agent & verification & identity & evidence & campaign
  agent --> browser & llm & mail & oracle & evidence & identity & campaign
  verification --> browser & oracle & evidence & campaign
  reporting --> evidence
  explorer --> browser & llm & campaign & evidence & sqlite
  identity & evidence --> sqlite
  campaign & identity & evidence & mail & oracle & browser & llm --> core[core/domain]
```

Inside a feature, `domain` imports nothing from `application` or `infrastructure`, and nothing from frameworks.
`application` never imports any `infrastructure`. Only `app` may import another feature's `infrastructure`.
`e2e/src/test/kotlin/.../ArchitectureTest.kt` enforces these rules with Konsist.

## Run lifecycle (`petek run`)

```mermaid
sequenceDiagram
  participant CLI as app (CLI)
  participant R as CampaignRunner
  participant I as Identity
  participant B as BrowserEngine
  participant A as Agents (xN)
  participant V as Verify
  participant F as RunFinalizer
  CLI->>R: run(campaign)
  R->>I: plan identities (seed, run tag)
  R->>B: start shared Chromium
  loop setup, then steps
    R->>A: resolve actors, wait_for, perform do/run (parallel)
    A-->>R: ActionOutcome (+ evidence)
    R->>R: emits → EventBus (t0)
    R->>V: assertions per actor / group
  end
  R->>R: teardown (finally, is_test only)
  R->>F: judge findings + write report
```

1. **Plan.** Load and validate the campaign. Derive the run tag from the run id and build the identity registry.
   Persist the run record and the identities.
2. **Browser.** Start one Chromium browser server. Each agent opens its own context with its own Playwright instance
   on its own single thread.
3. **Steps.** For each step:
   - Resolve its actors.
   - With `wait_for`, each actor waits on the event bus. The receipt is recorded when the event text shows up on
     screen (`visible_text`).
   - Each actor performs its `do` (LLM loop) or `run` (code). All actors of a step run concurrently. `parallel: true`
     additionally starts them at the same instant (a barrier), which race tests need.
   - With `emits`, the object id is read from the configured id source and the event is published with t0.
   - Assertions are evaluated per actor. `only_one_succeeds` is evaluated per group.
   - `on_fail: abort` stops the run; `continue` goes on.
4. **Watchdog.** An agent with no progress for `inactivityTimeout` is marked `blocked`. Its current action is cancelled
   and it moves to the next step.
5. **Teardown.** In `finally`, the test company recorded as a run resource is deleted. The oracle refuses companies
   that are not `is_test`.
6. **Finalize.** The judge turns assertion records into findings. The report (Markdown + HTML) is written to
   `evidence/<run_id>/report/`.

## Explorer (`features/explorer`, PLAN.md Faza 6)

The explorer learns a site from the owner's target URL and plain-language instructions and turns what it learned into
campaign drafts. The composition root (and later the web panel) drives it through three use cases:

- `ExploreSiteUseCase.execute(request, roleSessions, observer)` runs the requested phases in order:
  - **ANONYMOUS**: its own browser session crawls without logging in.
  - **ROLE_BASED**: the caller's logged-in sessions (role name → session) crawl again. Comparing what each role reached
    and was offered fills `reachableBy` and infers `forbiddenRoles`.
  - **TRIAL_TOUCH**: submits each observed CREATE form once with harmless data. It runs only with `allowWrites` *and* a
    `TestTargetCheck` confirmation that the target holds test data (`is_test`); otherwise it is skipped with the reason.
    Only the logged-in role sessions write, because teardown removes only the test company's data. The anonymous
    session only watches for live effects. Only forms that code classified CREATE are submitted, never on an LLM's
    say-so. Sign-up and sign-in forms (also
    without a password field), forms sent to another site, and forms sent with DELETE/PUT/PATCH (`_method` overrides
    and `formaction`/`formmethod` included) are never submitted. Nothing is typed when the browser does not land on the
    page where the form was seen.
- `GenerateScenarioUseCase` turns the model's top test ideas (`TestPatternLibrary`, grounded by the instructions) into a
  campaign YAML draft. The draft is assembled by code and validated with `DefaultCampaignValidator` before it is stored.
  Real-time checks follow their creating step directly, because `visible_text` is measured from t0. Receivers open the
  page in a step before the creation. Oracle ids and checks are used only for resources that the target's test API
  serves. Ideas a draft cannot express are skipped with the reason: objects nested in other objects, and selectors
  with braces.
- `CompareExplorationsUseCase` diffs two model versions of a target (Faza 7 "fərq kəşfiyyatı"). Partial models are
  skipped for the "latest" diff, including models that stopped at the page budget.

A crawl is breadth-first and one page per URL pattern: `/tickets/t1`, `/attendance/2026-09-25` and
`/tickets/123-noutbuk` each end in `{id}`. It stays within the page,
depth and time budgets, and links that match the instructions are opened first. Every address is checked with a GET
before it is opened. For each page, the explorer stores a screenshot and a DOM snapshot as evidence. Code heuristics
extract forms, fields and buttons. **One structured LLM question** per page adds a purpose, named actions and questions
for the owner, and code validates the answer: element refs must exist in the snapshot and kinds must be known. Code,
never the LLM, records findings (broken links, HTTP errors, slow pages, accessibility, leaked error text) and the
live-update transports seen in the traffic.

Safety is enforced in code:
- Crawls see the browser only through a read-only session. It cannot click, type, select, send non-GET requests or
  leave the target's origin.
- The crawl never follows these: logout, delete, unsubscribe, approve and reject links (English and Azerbaijani,
  also percent-encoded or in capitals), `/api/`, `/test/`, file downloads and `robots.txt` exclusions. A page that moves
  to another site after loading is not learned and is not shown to the LLM.
- Prompts show URL patterns instead of addresses, mask secret-looking field values and remove tokens.
- The target must pass `TargetPolicy`.

Every model element carries `OBSERVED`/`INFERRED` provenance and the artifact ids of its evidence. Each exploration
stores one model version: the previous version for the same target + 1. The version is marked `partial` when the
exploration timed out, was cancelled, failed or left pages unvisited at the page budget. Everything is stored in the process database by `SqliteExplorationRepository`, in the
tables `exploration`, `site_model_version`, `exploration_finding`, `exploration_event`, `exploration_artifact` and
`scenario_draft`. Events (`ExplorationEvent`) are numbered per exploration, stored before they are published, and
replayable, so a live view can catch up (`FlowExplorationObserver` offers them as a flow). A cancellation cannot
interrupt a write between storing an event and advancing its number.

## Decisions taken for the MVP (answers to the plan's open questions)

| Question | Decision |
|---|---|
| Invitation or company code? | Both. `campaign.registration` splits the 29 non-admins (default 15 invite / 14 company code). Managers always join by invitation: the `/join` form has no role field, so a company-code sign-up becomes an employee on the target. The validator therefore requires `registration.invite >= roles.manager`; the invitations left after the managers go to employees (seeded, spread over the departments), and company-code identities are employees only. Each identity carries its `RegistrationMode`, and `register_and_login` follows the matching flow. |
| Which real-time mechanism? | Pətək does not depend on it. Latency is measured in the DOM (t1 − t0). The transport (WebSocket, SSE or polling) is detected from network traffic and shown in the report. |
| Does the backend store "read" receipts? | Yes (confirmed). The `receipts` oracle assertion is part of the default campaign. |
| LLM provider? | Claude. Default is the Claude Code CLI (`claude -p`, the user's Claude plan). The Anthropic API (official Java SDK) can be used instead. Both sit behind `LlmClient`; other providers can be added later as new adapters. |

## Security

- **Secrets.** `.env` is git-ignored. Secrets are wrapped in `Secret` and never logged. The LLM never sees passwords
  or tokens: it types `{self.password}` and the harness substitutes it.
- **Target policy.** Production hosts are refused unless `PETEK_ALLOW_PRODUCTION=true`. Oracle deletes require
  `is_test=true`.
- **Claude CLI.** It runs with no tools (`--tools ""`), no MCP servers and no settings, and without session
  persistence. It is started via `ProcessBuilder` with an argument list (no shell).
- **Supply chain.** Dependencies are pinned in the version catalog. The Gradle distribution is checksum-verified.
  Mailpit is pinned to a version and bound to 127.0.0.1.
