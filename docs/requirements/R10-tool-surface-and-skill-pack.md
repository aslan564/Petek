# R10 — Any host AI drives Pətək (MCP, `--json`, skill pack); root cause happens in the host's repository

**Status:** Planned (Faza 11–12) · **ADRs:** 0009

## Requirement

Pətək should be usable "like a skill": installed into any project, started beside it, driven by whatever AI coding
agent the project uses (Claude Code, Codex, Gemini CLI, Cursor, Copilot), and able to hand that agent the evidence of
a finding so it can locate the cause in the project's own source code and propose a fix. Pətək itself stays the
product: the panel, engine, evidence and report are Pətək's; the AI is a caller.

## Why

The owner's words: run it inside the project, the panel opens, choose the site and what to test, the engine works,
reports come out — and let the AI check the found problems at code level so root causes are confirmed. Embedding a
particular coding agent would tie the product to a vendor (R09); the host AI already has the repository open.

## Architecture (ADR-0009)

- **Tool surface (Faza 11).** One set of use cases (`PanelBackend` today) behind three faces: the web panel, an MCP
  server (`petek mcp`, stdio, loopback only) and `--json` on every CLI command. Tools: `explore_site`,
  `list_unknowns`, `answer_unknown`, `generate_scenario`, `approve_scenario`, `run_campaign`, `get_run_status`,
  `get_findings`, `get_evidence`, `list_targets`, `teardown`. Writes require `allowWrites`; the target policy applies.
- **`FindingBundle`** (reporting domain): finding + step + request/response + screenshot path + A/B/C + evidence tier —
  the one object a host AI reads to root-cause in its repository. Pətək never reads the target's source itself.
- **Skill pack (Faza 12).** `petek init` writes `.petek/` (profile template, `petek.yaml`) and per-AI instruction
  files (`SKILL.md`, `AGENTS.md`, `CLAUDE.md` fragment, `.cursor/rules`, `GEMINI.md`, Copilot instructions) plus a
  `.mcp.json` entry; it appends, never overwrites. Role instructions (English): explorer, scenario author, judge,
  root-cause — each limited to Pətək's tools.
- **`petek dev`** starts the panel once the project's app answers its health URL.

## Modules touched

`dashboard` (or a `toolface` feature over `PanelBackend`), `reporting` (`FindingBundle`), `app/cli` (`--json`,
`mcp`, `init`, `dev`), `build-logic` (distribution, Faza 12).

## Verification (planned)

- MCP handshake and per-tool contract tests over stdio; `--json` schema tests; `petek init` on an empty Node and Spring
  project in a temp dir; an MCP client (Claude Code `.mcp.json`) runs `explore_site` → `get_findings` on the fake target.

## Open items

- Kotlin MCP SDK (new dependency, rule 11) vs a thin stdio JSON-RPC implementation — owner's decision pending.
