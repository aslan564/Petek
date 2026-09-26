# R10 — Any host AI drives Pətək (MCP, `--json`, skill pack); root cause happens in the host's repository

**Status:** Skill pack done 2026-09-26 (`petek init`: `.env`, `.petek/petek.yaml`, `.petek/SKILL.md`, per-agent
fragments and MCP entries for Claude Code, Codex/AGENTS.md, Cursor, Gemini CLI, Copilot; detection by markers;
appends and refreshes, never overwrites); MCP server, `--json`, `FindingBundle` and `petek dev` planned (Faza 11–12) ·
**ADRs:** 0009

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
- **Skill pack (Faza 12, done).** `petek init [--target] [--ai ...] [--force] [--dir]` (`app/init/ProjectInitializer`)
  writes `.env` from the repository's `.env.example` (copied into the app's resources by the build; never rewritten
  once present), `.petek/petek.yaml` (profile: target, health URL, mail source, scenario directory) and
  `.petek/SKILL.md` (Agent Skills front matter; roles explorer, scenario author, judge, root-cause, the command/tool
  table and the rules the agent keeps). Per agent (`HostAi`, detected by markers `CLAUDE.md`/`.claude`, `AGENTS.md`/
  `.codex`, `.cursor`, `GEMINI.md`/`.gemini`, `.github/copilot-instructions.md`; default Claude Code + AGENTS.md): a
  fragment between `<!-- petek:begin -->`/`<!-- petek:end -->` in the instruction file (appended, replaced in place on
  a re-run, the owner's text untouched), the `petek` server (`petek mcp`) merged into the project MCP file
  (`.mcp.json`, `.cursor/mcp.json`, `.gemini/settings.json`, `.vscode/mcp.json` with its `servers` key; other servers
  kept; a file that is not JSON is left alone and reported) and, for Claude Code, `.claude/skills/petek/SKILL.md`.
  Files Pətək owns are rewritten only with `--force`; `.gitignore` gains `.env` and `evidence/`. Every file is reported
  as created / updated / kept / unchanged.
- **`petek dev`** starts the panel once the project's app answers its health URL.

## Modules touched

`dashboard` (or a `toolface` feature over `PanelBackend`), `reporting` (`FindingBundle`), `app/cli` (`--json`,
`mcp`, `init`, `dev`), `build-logic` (distribution, Faza 12).

## Verification

- Done: `ProjectInitializerTest` (empty project; owner's `CLAUDE.md` and `.gitignore` gain the fragment once and keep
  their text, a stale fragment is replaced in place, a third run writes nothing; `.env` kept, profile rewritten only
  with `--force`; detection from `.cursor` and `GEMINI.md`; `.mcp.json` keeps other servers and keys, Copilot's
  `servers` shape; a broken JSON file is left alone), `InitCommandTest` (no configuration loaded, `--dir`, unknown
  `--ai` refused).
- Planned: MCP handshake and per-tool contract tests over stdio; `--json` schema tests; an MCP client (Claude Code
  `.mcp.json`) runs `explore_site` → `get_findings` on the fake target.

## Open items

- Kotlin MCP SDK (new dependency, rule 11) vs a thin stdio JSON-RPC implementation — owner's decision pending.
