# Pətək

**Pətək** (Azerbaijani for *beehive*) is a multi-agent AI test platform. It puts many AI tester agents on a web
application at the same time, each in its own isolated browser session, coordinates them like a real team of users
(one publishes, twenty-nine must see it; two approve the same request at once; an employee tries an admin's page),
measures what happens with a clock the model never touches, and writes a report in which every verdict is backed by
evidence: a screenshot, a DOM read, a network exchange or the answer of the target's own test API.

Azərbaycanca: [README.az.md](README.az.md).

- **Product, not a prompt.** Pətək is a running program with its own web panel, browser fleet, evidence database and
  report. The AI is a component inside it, not the other way round.
- **Bring your own AI.** The agents think with whatever AI your project already uses (Claude today; Codex, Gemini,
  Ollama and any OpenAI-compatible endpoint on the roadmap). Pətək never carries the model cost or sees your data twice.
- **Code decides, not the model.** Time is measured by the harness, assertions are evaluated by code, and an agent can
  only perform actions from a code-owned whitelist. Which AI is used does not change what a verdict is worth.
- **Any site of yours.** The first target is KadroHR (an HR SaaS). Sites are described as data (`target_profile`),
  and the roadmap makes the engine target-agnostic.

Author: **Aslan Aslanov** · © 2026 **Kodcraft** · Licensed under the [Business Source License 1.1](LICENSE)
(converts to Apache 2.0 on 2030-09-25).

---

## Contents

1. [Why](#why)
2. [How it works](#how-it-works)
3. [What is in the box](#what-is-in-the-box)
4. [Quick start](#quick-start)
5. [Configuration](#configuration)
6. [Scenarios](#scenarios)
7. [The target contract](#the-target-contract)
8. [The web panel](#the-web-panel)
9. [Architecture](#architecture)
10. [Security](#security)
11. [Roadmap](#roadmap)
12. [Documentation](#documentation)
13. [Development](#development)
14. [Licence, trademark and copyright](#licence-trademark-and-copyright)

---

## Why

Multi-user and real-time defects do not show up when one person clicks through a site: an announcement that never
reaches half the receivers, a ticket status that two managers change at once, a permission check that a direct URL
bypasses. Testing this by hand needs thirty people and thirty devices, every release. Pətək replaces the people with
agents and the devices with isolated browser contexts, keeps the tests as versioned scenarios, and turns each run into
evidence a developer can act on.

## How it works

```
┌──────────────────────────────────────────────────────────────┐
│ Host AI (Claude Code / Codex / Gemini CLI / Cursor ...)       │  roles: explorer, scenario author, judge, root-cause
│  reads Pətək's skill pack, calls Pətək through MCP / --json   │  (roadmap Faza 11–12)
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│ Pətək engine + panel (deterministic, evidence-based)          │  explore · plan · run · report · teardown
│  identities · browser fleet · event bus · clock · assertions  │  target profiles · mail/OTP sources · oracle
│  evidence store (SQLite + files) · three-source judge · report│
└───────────────┬──────────────────────────────────────────────┘
                │ LlmClient (structured output, whitelisted actions)
┌───────────────▼──────────────────────────────────────────────┐
│ Swarm brain: the `do` steps of N tester agents                │  your project's own AI, metered per agent
└──────────────────────────────────────────────────────────────┘
```

A run (`petek run scenarios/<campaign>.yaml`):

1. **Identities.** The orchestrator derives N deterministic testers (names, e-mails on your test domain, passwords via
   HMAC of a secret, phones, roles, departments, registration mode). Agents only read their identity.
2. **Browsers.** Chromium browser servers are started and sharded by load; every agent gets one isolated context, and
   every Playwright call of a session runs on that session's own single-thread dispatcher.
3. **Steps.** Deterministic `run` steps (sign-up, login, join by invitation or company code) execute the target's flows
   as data; `do` steps ask the AI for one whitelisted action at a time, with a numbered snapshot of the page. `emits`
   stamps t0 on the harness clock; `wait_for` in every receiver measures t1 in the DOM.
4. **Verification.** Typed assertions (`visible_text`, `not_visible`, `count`, `latency_max`, `http_status`,
   `only_one_succeeds`, `oracle`) are evaluated by code. The three-source judge compares what the sender did (A), what
   the receiver saw (B) and what the target's test API says (C) and classifies findings as backend, delivery/UI or
   investigate.
5. **Report and teardown.** Markdown + HTML report with steps, screenshots, latency distribution, findings with A/B/C,
   failed agents, stability over `--repeat`, tokens and cost per agent. Test data is deleted through the test API,
   only on companies flagged `is_test`.

## What is in the box

| Area | Today |
|---|---|
| CLI | `panel` (default), `doctor`, `capacity`, `plan`, `smoke`, `run --repeat N --testers N`, `report`, `teardown`, `probe` |
| Web panel | Instructions, Explorer, Scenarios (draft → approve → freeze, diff, triage), Orchestrator task matrix, live Agents board with screenshots, Reports and stability |
| Explorer | Learns a site model (pages, forms, actions, roles, realtime, unknowns) in three phases, asks the owner about unknowns, derives test ideas, drafts a campaign |
| Triage | Sorts a run's surprises into system bug / model gap / scenario bug and proposes scenario v2 as a reviewable diff |
| Targets | KadroHR (real, `scenarios/kadrohr.yaml`) and a fake contract site (`testing/fake-target`) for e2e |
| Mail / OTP | Mailpit catch-all inbox or the target's test API (`PETEK_MAIL_SOURCE`); phone OTP from the test API |
| AI | Claude Code CLI (`claude -p`, your Claude plan) or the Anthropic API, behind one `LlmClient` port with retry, concurrency limit and metering |
| Evidence | SQLite (runs, identities, steps, events, receipts, assertions, findings, usage) + artifact files, every record with an id |
| Quality gates | Kotlin warnings as errors, ktlint via Spotless, licence headers enforced, Konsist architecture tests, Kover coverage, e2e with real Chromium |
| Isolation | Every tester in its own browser context and thread, knowing colleagues without their secrets; shared values write-once; proven with 1 000 testers through the orchestrator on every build, 5 000 and 30 real Chromium sessions in CI (measured up to 60) — see [R01](docs/requirements/R01-concurrent-multi-agent-testing.md) |

## Quick start

Prerequisites: JDK 21+ to run Gradle (the build downloads its own JDK 25 toolchain), Docker (for Mailpit) and an AI:
the [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) logged in with your plan, or an Anthropic API key.

```bash
git clone https://github.com/aslan564/Petek.git && cd Petek
docker compose up -d                                   # Mailpit on :1025 (SMTP) / :8025 (API)
cp .env.example .env                                   # fill PETEK_TARGET, PETEK_TEST_TOKEN, PETEK_IDENTITY_SECRET
./gradlew :app:run --args="doctor"                     # target policy, target, Chromium, inbox, test API, AI provider
./gradlew :app:run                                     # opens the web panel at http://127.0.0.1:7070
```

No target yet? The panel starts a **local fake KadroHR** when there is no `.env` (or with `--demo`), so the whole
loop (explore → draft → approve → run → report) works on your machine without any external system:

```bash
./gradlew :app:run --args="panel --demo"
# or run the fake site on its own and point a .env at it
./gradlew :testing:fake-target:run                     # http://127.0.0.1:18080, mail API :18025
./gradlew :app:run --args="--env-file .env.fake-target doctor"
./gradlew :app:run --args="--env-file .env.fake-target run scenarios/contract-demo.yaml --testers 6"
```

Command line, end to end:

```bash
./gradlew :app:run --args="capacity"                   # how many testers this machine can take (advice, not a limit)
./gradlew :app:run --args="plan scenarios/kadrohr.yaml" # the identities a run would create, nothing executed
./gradlew :app:run --args="run scenarios/kadrohr.yaml --repeat 3"
./gradlew :app:run --args="report latest"
./gradlew :app:run --args="teardown --run <run_id>"    # remove the test company (also done at the end of every run)
```

Exit codes: `0` success, `1` failures found, `2` configuration error or aborted run, `130` interrupted.

## Configuration

Everything comes from `.env` (or `--env-file`) and the environment; real environment variables win. Secrets travel as
`Secret` and never reach logs or the AI. `PETEK_TARGET` is the only required key.

| Key | Default | Meaning |
|---|---|---|
| `PETEK_TARGET` | — | The system under test; replaces `campaign.target` of every campaign |
| `PETEK_PRODUCTION_HOSTS` | `kadrohr.com,www.kadrohr.com` | Hosts refused as a target unless … |
| `PETEK_ALLOW_PRODUCTION` | `false` | … this is `true` (rule 8) |
| `PETEK_TEST_TOKEN` | — | `X-Test-Token` for the target's `/test/...` API; empty disables oracle checks and teardown |
| `PETEK_TEST_API_URL` | the target | Base address of the `/test/...` API when it is not on the target's origin |
| `PETEK_MAIL_SOURCE` | `mailpit` | `mailpit` or `test-api` (`GET /test/emails`, needs the token) |
| `PETEK_MAILPIT_URL` | `http://localhost:8025` | Mailpit API |
| `PETEK_MAIL_DOMAIN` | `test.kadrohr.com` | E-mail domain of the test identities |
| `PETEK_IDENTITY_SECRET` | `~/.petek/identity.secret` | Key of the password derivation (≥ 16 chars) |
| `PETEK_LLM_PROVIDER` | `claude-cli` | `claude-cli` or `anthropic-api` (`auto` and more providers: roadmap Faza 9) |
| `PETEK_LLM_MODEL` | `claude-sonnet-5` | Model id |
| `PETEK_CLAUDE_BIN` | `claude` | The CLI binary |
| `PETEK_LLM_CONCURRENCY` | `6` | AI calls in flight across all agents (1–64) |
| `ANTHROPIC_API_KEY` | — | For `anthropic-api` |
| `PETEK_BROWSER_HEADLESS` | `true` | `--headful` on `run` overrides it |
| `PETEK_BROWSER_TOPOLOGY` | `shared-server` | or `per-session` |
| `PETEK_EVIDENCE_DIR` / `PETEK_DB` | `evidence` / `evidence/petek.db` | Where evidence, reports, logs and the database live |

## Scenarios

A campaign is a YAML file: who the testers are, how they register, what they do and what must be true. Everything
deterministic is a `run` step (executed by code from the target's flows); only what needs judgement is a `do` step.

```yaml
campaign:
  name: contract-demo
  testers: 30
  roles: {admin: 1, manager: 5, employee: 24}
  departments: [IT, HR, Satış, Maliyyə, Əməliyyat]
  registration: {invite: 15, company_code: 14}
  budget: {max_steps_per_agent: 60, max_minutes: 40}
setup:
  - {id: owner_signup, actor: admin, run: register_owner}
  - {id: seed,         actor: admin, run: seed_company}
  - {id: join,         actor: "employee[*] | manager[*]", run: register_and_login}
steps:
  - id: announce
    actor: admin
    do: "Publish an announcement titled 'Pətək test'"
    emits: announcement_created
  - id: receive
    actor: "employee[*] | manager[*]"
    wait_for: announcement_created
    assert:
      - {visible_text: "Pətək test", timeout_s: 30}
      - {latency_max: 5000}
```

`target_profile` describes the site itself: paths, selectors, sign-up/login flows, overlays to dismiss, the API
prefix and where created ids are read from. `scenarios/kadrohr.yaml` describes the real KadroHR entirely this way
(no code), `scenarios/contract-demo.yaml` the contract site. The full format, actor grammar, templates and
assertion types are in [docs/PLAN.md](docs/PLAN.md) ("Ssenari formatı") and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## The target contract

Pətək works best when the target offers a **test mode**: a `/test/...` API behind `X-Test-Token` (OTP codes,
companies, seeding, announcements with read receipts, tickets, notifications, optionally mail), `is_test` companies,
and stable `data-testid`s. [docs/TARGET_CONTRACT.md](docs/TARGET_CONTRACT.md) specifies it; `testing/fake-target`
implements it; [docs/KADROHR_READINESS.md](docs/KADROHR_READINESS.md) tracks the real KadroHR. Without a test API,
page and network checks still work, oracle checks are skipped and teardown is impossible (the roadmap makes this a
first-class mode with evidence tiers).

## The web panel

`petek` with no arguments (or `petek panel`) starts a loopback-only Ktor server and opens the browser. Screens:
**Təlimat** (target, plain-language instructions, team, budget, "explore"), **Kəşfiyyat** (the explorer live: phases,
site model, findings, questions to answer), **Ssenarilər** (versions, YAML, diff, approve/freeze, triage),
**Orkestrator** (step lanes × agents task matrix, timeline), **Agentlər** (live board with screenshots),
**Hesabatlar** (history, cost, stability). Non-GET requests need the per-start `X-Petek-Token`; foreign `Host`/`Origin`
headers are refused.

## Architecture

Feature-based clean architecture in Gradle modules: every capability is `features/<name>` with `domain`
(pure Kotlin: model, ports) → `application` (use cases) → `infrastructure` (Playwright, Ktor, Exposed, SDKs).
Dependencies go through ports with constructor injection; `app/` is the only composition root; there is no DI
framework. Konsist tests in `e2e/` fail the build when a layer rule is broken.

| Module | Responsibility |
|---|---|
| `core/domain`, `core/sqlite` | Ids, harness clock, `Secret`, `TargetPolicy`; one SQLite database per evidence dir |
| `features/campaign` | Campaign model, actor grammar, templates, validation, target profile and flows |
| `features/identity` | Deterministic identity registry |
| `features/browser` | Isolated Playwright sessions, snapshots, real-time transport detection, race evidence |
| `features/llm` | `LlmClient` port, Claude CLI and Anthropic API adapters, retry/limit/metering decorators |
| `features/agent` | Action whitelist, decision protocol, agent loop, `run` functions over target flows |
| `features/mail`, `features/oracle` | Inbox sources and the target's test API |
| `features/verification` | Typed assertions and race verdicts |
| `features/orchestration` | Run lifecycle, event bus, scheduler, watchdog, teardown, live board |
| `features/evidence`, `features/reporting` | Evidence store; three-source judge, stability, reports |
| `features/explorer`, `features/scenarios` | Site model and drafts; versioned scenarios and triage |
| `features/dashboard`, `features/capacity` | Web panel; capacity advice |
| `app`, `testing/fake-target`, `e2e` | CLI and wiring; the contract site; architecture and e2e tests |

Details, diagrams and the module table: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Decisions: [docs/adr](docs/adr).
Requirement-by-requirement architecture: [docs/requirements](docs/requirements).

## Security

- Secrets (`PETEK_TEST_TOKEN`, API keys, test passwords) are `Secret` values: never logged, never sent to the AI; the
  agent types `{self.password}` and the harness substitutes it.
- Production hosts are refused unless explicitly allowed; oracle writes and teardown only touch `is_test` companies.
- The AI runs with no tools, no MCP servers, no settings and no session persistence; processes are started without a
  shell; the environment is scrubbed.
- The panel binds to loopback only, requires a per-start token for writes and rejects foreign origins.
- Dialog messages and page text are masked for secrets before they reach evidence or the AI.

Threat model, controls and how to report a vulnerability: [SECURITY.md](SECURITY.md).

## Roadmap

Phases 0–7 (MVP, explorer, triage, web panel) are implemented. The "Pətək 2" plan in [docs/PLAN.md](docs/PLAN.md):

| Phase | Goal |
|---|---|
| 8 | Foundation fixes; licence, `workspace_id`, edition ports, opt-in telemetry |
| 9 | AI-provider-agnostic layer: `PETEK_LLM_PROVIDER=auto`, generic CLI agents, OpenAI-compatible HTTP |
| 10 | Target profiles, sign-in strategy chain (test company → own accounts → self sign-up → anonymous), IMAP and manual OTP, evidence tiers |
| 11 | Tool surface: MCP server and `--json` CLI so any host AI can drive Pətək |
| 12 | Skill pack (`petek init`), distribution (Docker, CLI, `petek dev`), CI mode, shareable reports |
| 13 | Universal target model: tenant-optional core, free-form roles, blind test patterns |
| 14 | Ecosystem: contract kits per stack, log correlation bridge, regression baselines, hosted swarm (paid) |

## Documentation

| Document | What it holds |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | The plan: goals, scope, design decisions, scenario format, phases 0–14, success criteria (Azerbaijani) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, dependency rules, run lifecycle, agent loop, target flows, explorer, panel, security notes |
| [docs/requirements](docs/requirements) | One architecture document per requirement with traceability to modules, tests and ADRs |
| [docs/adr](docs/adr) | Architecture decision records 0001–0011 |
| [docs/TARGET_CONTRACT.md](docs/TARGET_CONTRACT.md) | What a target offers in test mode |
| [docs/KADROHR_READINESS.md](docs/KADROHR_READINESS.md) | The real KadroHR: what is done, what the target still needs |
| [SECURITY.md](SECURITY.md) · [CONTRIBUTING.md](CONTRIBUTING.md) · [CLAUDE.md](CLAUDE.md) | Security policy; how to contribute; the rules AI coding agents follow in this repository |

## Development

```bash
./gradlew build                 # compile, unit tests, ktlint, licence headers, architecture tests, coverage
./gradlew spotlessApply         # format and add the licence header to new files
./gradlew e2eTest               # fake target + real Chromium: panel end to end, e2e module, 30-session isolation proof
./gradlew :e2e:liveTest         # real AI provider (uses your plan or key)
```

Rules that keep the code base healthy (enforced by the build where possible): Kotlin warnings are errors; every source
file carries the licence header; domain code imports no framework; application code never imports infrastructure;
only `app` wires infrastructure; no mocking library (fakes live in `testFixtures`); tests are named as sentences; new
libraries need the owner's approval; `./gradlew spotlessApply build` must pass before every commit. The full list is in
[CONTRIBUTING.md](CONTRIBUTING.md). Branches: `develop` is the integration branch; `petek-mvp` and
`petek-mvp-o6tpsw` are kept as the MVP history.

## Licence, trademark and copyright

Copyright © 2026 **Kodcraft**. Author: **Aslan Aslanov**. All rights reserved.

Pətək is licensed under the **Business Source License 1.1** ([LICENSE](LICENSE)). You may use, copy, modify and
redistribute it, and make production use of it to test software you own or operate, but you may not offer Pətək or a
product whose value derives substantially from it as a hosted, managed or embedded service to third parties. On
**2030-09-25** the licence converts to the **Apache License, Version 2.0**. Every source file carries the header that
Spotless enforces. "Pətək" is a trademark of Kodcraft ([NOTICE](NOTICE)). For commercial licensing:
aslanovaslan165@gmail.com.
