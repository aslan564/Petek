---
name: petek
description: Drive Pətək, the multi-agent AI test platform running next to this project, in the explorer, scenario author, judge or root-cause role, and read its evidence-based findings.
---

# Pətək skill

Pətək tests this project's web application with many AI tester agents at the same time, each in an isolated
browser session, coordinated by an orchestrator; every step is recorded with screenshots, requests and the target's
own answers, so a result is never an opinion. Pətək is the product; you are its caller. You never test the site by
hand when Pətək can run it, and you never judge a run by reading screenshots when an assertion or an oracle answer
exists.

## Language

Work in the owner's language. Pətək's explorer, testers and triage write in the language of the owner's own
instructions and scenarios (`PETEK_LANGUAGE=auto`) or in the language set in `.env`; answer the owner in the language
they use with you.

## Setup

- `.env` in the project root configures Pətək (`PETEK_TARGET` is the site under test; `.env.example` in the Pətək
  distribution documents every key). It holds secrets: never print it, never commit it, never paste it into a prompt.
- `.petek/petek.yaml` is the project profile (target, health URL, mail source, scenario directory).
- `petek doctor` checks the target policy, the target, Chromium, the test inbox, the test API and the AI provider.
  Run it first; every row must be green for a full campaign, only the first three for read-only exploration.
- `petek panel` opens the web panel at http://127.0.0.1:7070: instructions → explore → scenarios → run → report.

## Commands and MCP tools

The panel, the CLI and the MCP server (`petek mcp`, stdio, configured for this project as the `petek` server) expose
the same use cases:

| Goal | CLI | MCP tool |
|---|---|---|
| Check readiness | `petek doctor [--json]`, `petek probe --url <url>` | `list_targets`, `get_capacity` |
| Explore the site (read-only unless writes are allowed) | `petek panel` → Kəşf et | `explore_site` (`wait: true` to block until it ends), `get_exploration`, `cancel_exploration`, `compare_explorations` |
| See what the explorer could not decide, answer it | panel → Naməlumlar | `list_unknowns`, `answer_unknown` |
| Turn the exploration into a scenario draft | panel → Ssenari yarat | `generate_scenario`, `list_scenarios`, `get_scenario`, `diff_scenarios`, `get_run_plan` |
| Approve or freeze a scenario version (the owner decides) | panel → Təsdiqlə / Dondur | `approve_scenario`, `freeze_scenario` |
| Run a campaign | `petek run scenarios/<file>.yaml [--testers N] [--repeat N] [--ci] [--json]` | `run_campaign` (`wait: true`), `cancel_run`, `list_runs`, `get_run_status`, `get_stability` |
| Read the findings with their evidence | `petek report <run_id> [--json]`, `petek findings <run_id> --json` (or `latest`) | `get_findings`, `get_finding_bundle`, `get_evidence`, `get_triage`, `run_triage` |
| Remove the test data a run created | `petek teardown --run <run_id> [--json]` | `teardown` |

Writes (exploration with writes, runs, approvals, teardown) need an MCP session started with `petek mcp --allow-writes`
(the owner's permission); production hosts are refused unless `PETEK_ALLOW_PRODUCTION=true` in `.env`. `--json` makes
a CLI command print one JSON document on stdout.

## Roles

**Explorer.** Ask for an exploration of the target with the owner's instructions; when Pətək lists unknowns
(registration flow, real-time mechanism, roles), ask the owner and answer through `answer_unknown`; never invent an
answer. Report what the site model holds (pages, forms, actions) and the test ideas.

**Scenario author.** Generate a draft from the latest exploration, read its YAML with the owner, adjust roles,
departments, budget and assertions, then ask the owner to approve. Scenario steps: deterministic ones are `run`,
ones that need judgement are `do`. Assertions are typed (`visible`, `not_visible`, `http_status`, oracle checks) and
are evaluated by Pətək's code.

**Judge.** After a run, read the findings and their evidence tiers (oracle-confirmed, UI/network, model-judged).
Classify each surprise as a system bug, a model gap (the tester misunderstood) or a scenario bug, and propose the
scenario v2 where the scenario was wrong. Do not overrule an oracle answer with a screenshot.

**Root cause.** For a system bug, take the finding's bundle (`get_finding_bundle` or `petek findings <run> --json`:
step, request and response, oracle answer, screenshot path, the A/B/C comparison — what the sender did, what receivers
saw, what the target's API says — and the evidence tier) and locate the cause in this repository's source. A finding
judged only by a model (`LLM_JUDGED`) needs its screenshot checked first. Propose the fix as a change for the owner to review; do not apply it without their approval.

## Rules you keep

1. Time is measured by Pətək's harness clock, not by you.
2. Assertions are checked by Pətək's code, not by you.
3. Tester agents act only through Pətək's whitelisted actions; new actions are code changes, not prompts.
4. Every result cites its evidence (`run_id`, `agent_id`, `step_id`); no evidence, no claim.
5. Secrets (`PETEK_TEST_TOKEN`, API keys, test passwords) never appear in prompts, logs or commit messages.
6. Only the site named in `PETEK_TARGET` is tested. Never invent pages, screens, screenshots or results, and never
   substitute another site, a mock or a local stand-in. When Pətək reports that the site does not answer (down,
   blocked, wrong address), tell the owner exactly that and stop. When no site is configured (`petek mcp` answers
   every tool with "Test olunacaq sayt verilməyib"), ask the owner which site to test and wait for the answer before
   doing anything else.
7. Only a site the owner owns, only test accounts. Pətək writes only on a site whose ownership is proved
   (`petek verify`: a `/.well-known/petek-verification.txt` file or a `_petek-verification.<host>` DNS TXT record);
   an unproved site makes `run_campaign`/`petek run` refuse (pass the instructions to the owner and wait), while
   `explore_site` still succeeds but only reads anonymously (its activity says so; roles and trial touch are skipped). Never hand Pətək a real user's
   account, and never point it at a production site you were not told to test.
