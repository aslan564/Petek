# Pətək

**Pətək** (Azerbaijani for *beehive*) is a multi-agent AI test platform. It puts many AI tester agents on a web
application at the same time, each in its own isolated browser session, coordinates them like a real team of users
(one publishes, twenty-nine must see it; two approve the same request at once; an employee tries an admin's page),
measures what happens with a clock the model never touches, and writes a report in which every verdict is backed by
evidence: a screenshot, a DOM read, a network exchange or the answer of the target's own test API.

Azərbaycanca: [README.az.md](README.az.md).

- **Product, not a prompt.** Pətək is a running program with its own web panel, browser fleet, evidence database and
  report. The AI is a component inside it, not the other way round.
- **Bring your own AI, any one.** Pətək is tied to no AI vendor: the agents think with whatever AI you have, an AI
  command-line tool you are logged in to (described in `.env`, or Codex, Gemini and OpenCode found by name) or any
  OpenAI-compatible API (OpenAI, Grok, OpenRouter, Ollama, ...). When one is unavailable, the next one found answers.
  Pətək never carries the model cost or sees your data twice.
- **Code decides, not the model.** Time is measured by the harness, assertions are evaluated by code, and an agent can
  only perform actions from a code-owned whitelist. Which AI is used does not change what a verdict is worth.
- **Any site of yours.** The first target is KadroHR (an HR SaaS). Sites are described as data (`target_profile`),
  and the roadmap makes the engine target-agnostic.

Author: **Aslan Aslanov** · © 2026 **Kodcraft** · Open source under the [Apache License 2.0](LICENSE).

---

## Contents

1. [Why](#why)
2. [How it works](#how-it-works)
3. [What is in the box](#what-is-in-the-box)
4. [Quick start](#quick-start)
5. [Use it on your own site](#use-it-on-your-own-site)
6. [Configuration](#configuration)
7. [Scenarios](#scenarios)
8. [The target contract](#the-target-contract)
9. [The web panel](#the-web-panel)
10. [Architecture](#architecture)
11. [Security](#security)
12. [Roadmap](#roadmap)
13. [Documentation](#documentation)
14. [Development](#development)
15. [Licence, trademark and copyright](#licence-trademark-and-copyright)

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
│ Host AI (your coding agent: Codex / Gemini CLI / Cursor ...)  │  roles: explorer, scenario author, judge, root-cause
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
| CLI | `panel` (default), `init`, `verify`, `doctor`, `dev`, `capacity`, `plan`, `smoke`, `run --repeat N --testers N --ci --swap-accounts`, `report`, `findings`, `teardown`, `probe`, `mcp` |
| Web panel | Instructions, Explorer, Scenarios (draft → approve → freeze, diff, triage), Orchestrator task matrix, live Agents board with screenshots, Reports and stability |
| Explorer | Learns a site model (pages, forms, actions, roles, realtime, unknowns) in three phases, asks the owner about unknowns, derives test ideas, drafts a campaign |
| Triage | Sorts a run's surprises into system bug / model gap / scenario bug and proposes scenario v2 as a reviewable diff |
| Targets | KadroHR (real, `scenarios/kadrohr.yaml`) and a fake contract site (`testing/fake-target`) for e2e |
| Mail / OTP | Mailpit catch-all inbox or the target's test API (`PETEK_MAIL_SOURCE`); phone OTP from the test API |
| AI | Whatever you have (`PETEK_LLM_PROVIDER=auto`): any AI command-line tool (`PETEK_LLM_BIN` + `PETEK_LLM_ARGS`), the Codex, Gemini or OpenCode CLI, the Anthropic API, or any OpenAI-compatible endpoint (OpenAI, Grok, OpenRouter, Ollama, LM Studio, vLLM), behind one `LlmClient` port with retry, concurrency limit, metering and fallback to the next AI found; `doctor` says which one and why |
| Evidence | SQLite (runs, identities, steps, events, receipts, assertions, findings, usage) + artifact files, every record with an id |
| Quality gates | Kotlin warnings as errors, ktlint via Spotless, licence headers enforced, Konsist architecture tests, Kover coverage, e2e with real Chromium |
| Isolation | Every tester in its own browser context and thread, knowing colleagues without their secrets; shared values write-once; proven with 1 000 testers through the orchestrator on every build, 5 000 and 30 real Chromium sessions in CI (measured up to 60) — see [R01](docs/requirements/R01-concurrent-multi-agent-testing.md) |

## Quick start

**In five minutes, next to your app.** With Node.js: `npx petek init --target https://staging.my-site.com` (writes
`.env`, `.petek/`, the skill pack and your AI's MCP entry), `npx petek verify` (proves the staging site is yours), then
`npx petek dev`: it waits for your app's health URL (`health_url` in `.petek/petek.yaml`) and opens the panel next to
it. The AI is whatever your project already uses (`PETEK_LLM_PROVIDER=auto`).

**From a release (no build, no JDK).** Download the bundle for your machine from the
[releases page](https://github.com/aslan564/Petek/releases): `petek-<version>-linux-x64.tar.gz`, `-linux-arm64.tar.gz`,
`-mac-arm64.tar.gz` or `-win-x64.zip` (each carries its own Java runtime and Chromium driver; `SHA256SUMS` lists the
checksums), or `petek-<version>-any-jdk25.zip` for any other machine with JDK 25 on `PATH`. Extract it anywhere and
have an AI, any one: an AI command-line tool you are logged in to, or an API key for an OpenAI-compatible service
(see `.env.example`). Chromium is downloaded by Playwright on first use.

```bash
tar xzf petek-0.1.0-linux-x64.tar.gz && cd my-site     # any directory: Pətək runs next to the site, never inside its build
cp ../petek-0.1.0-linux-x64/.env.example .env          # fill PETEK_TARGET (+ PETEK_TEST_TOKEN and PETEK_IDENTITY_SECRET for full runs)
../petek-0.1.0-linux-x64/bin/petek doctor              # target policy, target, Chromium, inbox, test API, AI provider
../petek-0.1.0-linux-x64/bin/petek panel               # opens the web panel at http://127.0.0.1:7070
```

On Windows the launcher is `bin\petek.cmd`. Extra JVM options go in `PETEK_OPTS`.

**With Node.js (`npx`).** The `petek` npm package is a launcher only: it downloads the bundle above once into
`~/.petek/versions/<version>`, checks its checksum and runs it, so nothing else is installed.

```bash
cd my-site
npx petek init --target https://staging.my-site.com   # .env, .petek/, skill pack + MCP entry for your AI agent
npx petek doctor
npx petek panel
```

**In Docker.** `ghcr.io/aslan564/petek:<version>` (linux/amd64 and linux/arm64) is the Linux bundle on Playwright's
official image, so Chromium and its libraries are already inside; mount the project as `/work` (`.env`, `scenarios/`,
and `evidence/` is written back). The panel binds loopback only, so it needs `--network host` (Linux); CI uses the
commands and `--json`. A ready workflow for the project under test is in `docs/ci/github-actions.yml`.

```bash
docker run --rm -v "$PWD:/work" --env-file .env ghcr.io/aslan564/petek:0.1.0 doctor
docker run --rm -v "$PWD:/work" --env-file .env ghcr.io/aslan564/petek:0.1.0 --json run scenarios/my-site.yaml
docker run --rm -v "$PWD:/work" --env-file .env --network host ghcr.io/aslan564/petek:0.1.0 panel --no-open
```

**From source.** Prerequisites: JDK 21+ to run Gradle (the build downloads its own JDK 25 toolchain) and the same AI;
Docker only when e-mail codes come through Mailpit (`docker compose up -d`, `PETEK_MAIL_SOURCE=mailpit`).

```bash
git clone https://github.com/aslan564/Petek.git && cd Petek
./gradlew :app:run                                     # no .env yet: the browser asks which site to test, then opens the panel
./gradlew :app:run --args="doctor"                     # target policy, target, Chromium, inbox, test API, AI provider
```

Pətək tests only the site you name. Without a `.env`, `petek panel` opens a page with one question, which site to
test, and starts nothing else until you answer: the address must answer (a site that is down, blocked or shows only a
CDN's error page is refused with the reason), then it is written to `.env` from `.env.example` (edit that file later
for a test API token, IMAP or Mailpit) and the panel opens for it. `cp .env.example .env` and editing by hand works
too. Without a `.env` the MCP server tells the host AI to ask you. A site that does not answer (down, blocked, wrong
address) is reported as such before any tester starts, never tested against something else; no screen or result is
ever invented. Pətək's own contract site
(`testing/fake-target`, the stand-in of its e2e suite) is for developing Pətək itself and is reached only through an
explicit configuration:

```bash
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

## Use it on your own site

Pətək is a **sidecar, not a library**: you do not add it to your site's Maven, npm or Composer build. It is installed
like a tool (a release bundle or `npx petek`, see R15), started next to the site, and pointed at the site's URL.
`petek init` prepares the project in one step: it writes `.env` from the template (never touched again), the profile
`.petek/petek.yaml`, the skill pack `.petek/SKILL.md` (roles: explorer, scenario author, judge, root-cause), and for
the AI coding agents it detects in the repository (or `--ai agents,cursor,gemini,copilot|all`) a marked fragment in
their instruction file (`AGENTS.md`, which most coding agents read, `.cursor/rules/petek.mdc`, `GEMINI.md`,
`.github/copilot-instructions.md`; appended, refreshed on a re-run, never overwriting your text) and the `petek` server
in their project MCP file (`.mcp.json`, `.cursor/mcp.json`, `.gemini/settings.json`, `.vscode/mcp.json`). `.env` and
`evidence/` go into `.gitignore`. It uses **your own AI login**, the way BMAD uses whatever assistant the project
already has: it calls the AI tool you are logged into (or the API key you give), and your plan pays for the model;
nothing is sent to Pətək's authors.

What your site needs, by depth of testing:

| You want | Your site needs | How |
|---|---|---|
| Read-only exploration: pages, forms, actions, a site model, test ideas, a scenario draft | Nothing. Anonymous pages are read as a visitor would. | `.env` with `PETEK_TARGET=https://your-site`, then `petek panel` → **Kəşf et** with the write box unticked |
| Logged-in exploration with roles | Accounts you can hand over, or self-registration Pətək can complete | Give Pətək the registration flow (test inbox for e-mail codes, phone OTP through the test API) or owner-provided logins (Faza 10) |
| Full campaigns: 30 testers, registration, OTP, assertions, real-time checks, teardown | The [target contract](docs/TARGET_CONTRACT.md): a `/test/...` API behind `X-Test-Token`, `is_test` companies, a catch-all inbox (Mailpit) or `GET /test/emails`; `data-testid`s are welcome but optional | Fill `PETEK_TEST_TOKEN`, `PETEK_MAIL_SOURCE`, `PETEK_IDENTITY_SECRET`; `petek doctor` must be all green |
| A production host | The explicit permission `PETEK_ALLOW_PRODUCTION=true` (hosts in `PETEK_PRODUCTION_HOSTS` are refused otherwise) | Only with a staging that speaks the contract, or read-only |

**Only your own site, only test accounts.** Pətək writes (runs, registrations, the explorer's roles and trial touch)
only on a site whose ownership is proved: `petek verify` prints a code to publish either as the file
`/.well-known/petek-verification.txt` or as the DNS TXT record `_petek-verification.<host>` (both containing
`petek-verification=<code>`), then checks it. `localhost`, loopback and private-network addresses need no proof. An
unproved site is only read, as an anonymous visitor would read it; `petek run` refuses it with exit code 2 and the
instructions. Point Pətək at a pre-production or staging copy you own, use test accounts only, and never hand it a real
user's account (ADR-0012). The code is made for the host exactly (lower case): `www.example.com` and `example.com`
are two hosts, each with its own proof; the file must be served from that origin without a redirect to another host.
A proof is remembered for 30 days; `petek verify` checks it again. `petek verify` exits with 1 while the proof is
missing, `petek run` with 2 when it refuses an unproved site.

Then the loop is the same for every site: `doctor` → `panel` → explore → answer the explorer's questions → send the
draft to scenarios → approve → run → report → let your AI read the findings' evidence (`FindingBundle`, Faza 11) and
fix the cause in your code.

## Configuration

Everything comes from `.env` (or `--env-file`) and the environment; real environment variables win. Secrets travel as
`Secret` and never reach logs or the AI. `PETEK_TARGET` is the only required key.

| Key | Default | Meaning |
|---|---|---|
| `PETEK_TARGET` | — | The system under test; replaces `campaign.target` of every campaign |
| `PETEK_TARGETS_DIR` | `targets` | Target profiles, one `targets/<name>.yaml` per site (URL, `api_url`, production hosts, mail, `${VAR}` token and account references, sign-in order, campaign profile); `PETEK_TARGET` may name one, and the panel runs any site that has one (see `targets/kadrohr.yaml`) |
| `PETEK_PRODUCTION_HOSTS` | `kadrohr.com,www.kadrohr.com` | Hosts refused as a target unless … |
| `PETEK_ALLOW_PRODUCTION` | `false` | … this is `true` (rule 8) |
| `PETEK_TEST_TOKEN` | — | `X-Test-Token` for the target's `/test/...` API; empty disables oracle checks and teardown |
| `PETEK_TEST_API_URL` | the target | Base address of the `/test/...` API when it is not on the target's origin |
| `PETEK_MAIL_SOURCE` | `mailpit` | `mailpit`, `test-api` (`GET /test/emails`, needs the token), `imap` (your own inbox) or `manual` (you type each code into the panel's "Kodu daxil et" box; for the explorer's 1–3 sessions) |
| `PETEK_MAIL_INBOX` | — | Your own box (`test@company.az`): each tester registers with `test+<run>-<agent>@company.az`; replaces `PETEK_MAIL_DOMAIN`; a site that refuses `+` is named in the report |
| `PETEK_IMAP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_TLS` / `_FOLDER` | — / 993 / the box / — / `true` / `INBOX` | How `imap` reads that box (Jakarta Mail/Angus); the password is a `Secret` |
| `PETEK_MAILPIT_URL` | `http://localhost:8025` | Mailpit API |
| `PETEK_MAIL_DOMAIN` | `test.kadrohr.com` | E-mail domain of the test identities |
| `PETEK_IDENTITY_SECRET` | `~/.petek/identity.secret` | Key of the test-password derivation **and** of the ownership code `petek verify` prints (≥ 16 chars). Keep it the same on every machine that tests the same site: another secret gives another code, and the published proof no longer matches |
| `PETEK_LLM_PROVIDER` | `auto` | `auto`, `cli`, `codex-cli`, `gemini-cli`, `opencode-cli`, `anthropic-api`, `openai-compat`, `none`; `auto` picks by settings and keys in the environment, your project's AI marker (`AGENTS.md`, `GEMINI.md`) and the agent CLIs on `PATH`, keeps the others as fallbacks, picks no vendor for you when nothing is found, and `doctor` says why |
| `PETEK_LLM_MODEL` | the tool's own | The model; empty keeps the one the tool or provider is configured for; required for `anthropic-api` and `openai-compat` |
| `PETEK_LLM_BIN` | — | The AI command-line tool to run (`cli`), or another binary for `codex-cli`, `gemini-cli`, `opencode-cli` |
| `PETEK_LLM_ARGS` | — | `cli` only: its arguments, with `{model}`, `{effort}`, `{system}`, `{schema}`, `{schema_file}`; the conversation goes to STDIN |
| `PETEK_LLM_ENV_UNSET` | — | Variables removed from the AI tool's environment, `NAME` or `PREFIX*` |
| `PETEK_LLM_BASE_URL` | — | An OpenAI-compatible endpoint: OpenAI, Ollama (`http://localhost:11434/v1`), Groq, Mistral, OpenRouter, LM Studio |
| `PETEK_LLM_API_KEY` | — | Key of `anthropic-api` / `openai-compat`; `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` are aliases |
| `PETEK_LLM_STRUCTURED` | `schema` | `schema`, `json_object`, `prompt`; a rejected mode steps down by itself |
| `PETEK_LLM_EFFORT` | `low` where supported | Reasoning effort (Codex CLI, `{effort}` of `cli`; `reasoning_effort` on `openai-compat`) |
| `PETEK_LLM_CONCURRENCY` | `6` | AI calls in flight across all agents (1–64) |
| `PETEK_LANGUAGE` | `auto` | What the AI writes for you in (explorer questions and ideas, tester summaries, triage): `auto` follows the language of your own instructions and scenarios, or a name such as `English` |
| `PETEK_BROWSER_HEADLESS` | `true` | `--headful` on `run` overrides it |
| `PETEK_BROWSER_TOPOLOGY` | `shared-server` | or `per-session` |
| `PETEK_BROWSER_IGNORE_TLS_ERRORS` | `false` | accept untrusted certificates (self-signed staging, re-signing proxy); `doctor` shows when it is on |
| `PETEK_CORRELATION_HEADER` / `PETEK_TRACE_LOG` | `false` / — | Testers send `X-Petek-Correlation-Id` (CORS must allow it); the site's log lines with a finding's id join its bundle (`petek findings`, MCP `get_finding_bundle`) |
| `PETEK_TELEMETRY` | `off` | `local`: counters only (AI calls, tokens, runs by provider and tester bucket) into `evidence/telemetry/usage.jsonl`; never content, nothing is sent |
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
page and network checks still work, oracle checks read "N/A (no oracle)" and teardown is impossible; every finding says
what its proof rests on (evidence tier: oracle-confirmed, screen/network, or a model's judgement).

## The web panel

`petek` with no arguments (or `petek panel`) starts a loopback-only Ktor server and opens the browser. Screens:
**Təlimat** (target, plain-language instructions, team, budget, "explore"), **Kəşfiyyat** (the explorer live: phases,
site model, findings, questions to answer), **Ssenarilər** (versions, YAML, diff, approve/freeze, triage),
**Orkestrator** (step lanes × agents task matrix, timeline), **Agentlər** (live board with screenshots),
**Hesabatlar** (history, cost, stability). Non-GET requests need the per-start `X-Petek-Token`; foreign `Host`/`Origin`
headers are refused.

## Reports, CI and findings for your AI

Every run writes into `evidence/<run>/report/`: `index.html` and `report.md` (the detail layer: every step, its proof,
latency, cost), `summary.html` (the customer layer: one page of short sentences on three shelves, *to fix on the site*,
*Pətək could not do it*, *a person should look*), `share.html` (one file with the screenshots inside, the AI provider and
model, and the evidence tiers, to send around), `junit.xml` (steps as test cases) and `findings.sarif` (findings for code
scanning). `petek run --ci` prints the JUnit and SARIF paths and adds the Markdown report to the GitHub job summary;
templates: `docs/ci/github-actions.yml`, `docs/ci/gitlab-ci.yml`. `petek findings <run|latest> --json` (and MCP
`get_finding_bundle`) gives your coding AI each finding with its step, request and response, oracle answer and screenshot
path, so it can look for the cause in your code.

## MCP server and `--json`

The same use cases have two more faces for a host AI (ADR-0009, R10). `petek mcp` is a Model Context Protocol server
over stdio (hand-rolled JSON-RPC, no extra dependency; `initialize`, `ping`, `tools/list`, `tools/call`), which
`petek init` registers as the `petek` server in the project's MCP file. Tools: `list_targets`, `get_capacity`,
`explore_site` (with `wait`), `get_exploration`, `cancel_exploration`, `list_unknowns`, `answer_unknown`,
`compare_explorations`, `generate_scenario`, `list_scenarios`, `get_scenario`, `diff_scenarios`, `get_run_plan`,
`approve_scenario`, `freeze_scenario`, `run_campaign` (with `wait`), `cancel_run`, `list_runs`, `get_run_status`,
`get_findings` (A/B/C sources and evidence ids), `get_evidence` (absolute path of a screenshot or capture), `get_triage`,
`run_triage`, `get_stability`, `teardown`. A session is read-only unless started with `petek mcp --allow-writes`:
runs, approvals, teardown and exploration with writes are refused otherwise, and the target policy applies as
everywhere. Every result carries the panel's JSON as text and structured content; a failure is an `isError` result with
the panel's message. Without `.env` the server uses the local fake KadroHR, like the panel.

`petek --json <command>` prints one JSON document on stdout for `doctor`, `init`, `plan`, `run`, `report` and
`teardown` (logs stay on stderr; a failure is `{"error": ...}` with the usual exit code), for scripts and CI.

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
| `features/llm` | `LlmClient` port, agent CLI profiles (any tool from `.env`, Codex, Gemini, OpenCode), Anthropic and OpenAI-compatible API adapters, retry/limit/metering/fallback decorators |
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
- Writes need proved site ownership (`petek verify`: a `/.well-known` file or a DNS TXT record); an unproved site is
  only read. Use a pre-production or staging site you own and test accounts only, never a real user's account.
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
| [SECURITY.md](SECURITY.md) · [CONTRIBUTING.md](CONTRIBUTING.md) · [AGENTS.md](AGENTS.md) | Security policy; how to contribute; the rules AI coding agents follow in this repository |

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
[CONTRIBUTING.md](CONTRIBUTING.md). Branches: `main` is the release branch (the Release workflow is started by hand
on it with the version and publishes the bundles, the image and the npm launcher); `develop` is the integration
branch; `petek-mvp` and `petek-mvp-o6tpsw` are kept as the MVP history. CI never runs on a push; the local
`./gradlew build` is the gate.

## Licence, trademark and copyright

Copyright © 2026 **Kodcraft**. Author: **Aslan Aslanov**.

Pətək is open source under the **Apache License, Version 2.0** ([LICENSE](LICENSE)): you may use, copy, modify and
redistribute it, commercially too, as long as you keep the licence and the notices ([NOTICE](NOTICE)). Every source
file carries the header that Spotless enforces. "Pətək" is a trademark of Kodcraft; the licence grants no right to its
name or logo (section 6). Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).
