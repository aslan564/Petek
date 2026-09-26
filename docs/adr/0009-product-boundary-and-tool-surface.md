# ADR-0009: Pətək is the product; the host AI is a caller — tool surface (MCP, `--json`) and skill pack

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
The owner wants Pətək to be usable "like a skill" by any AI coding agent (BMAD-style), yet remain a real product:
someone starts it in their project, the panel opens, they choose a site and what to test, the engine runs, and an
evidence-based report comes out. The idea and the value must stay in Pətək (UI, engine, evidence, report), not in the
model. Root-cause analysis at source-code level is wanted without embedding a coding agent inside Pətək.

## Decision
Three layers:
1. **Host AI** (any coding agent: Codex, Gemini CLI, Cursor, ...) reads Pətək's skill pack (`SKILL.md`, the
   `AGENTS.md` fragment, `.cursor/rules`, `GEMINI.md`) and plays roles: explorer, scenario author, judge, root-cause.
   It calls Pətək through the tool surface. Root cause happens here: the host AI already has the repository open;
   Pətək hands it a `FindingBundle` (finding, step, request/response, screenshot, A/B/C, evidence tier).
2. **Pətək engine + panel** exposes one set of use cases (`PanelBackend` today) through three faces: the web panel,
   an MCP server (`petek mcp`, stdio, loopback only) and `--json` on every CLI command. Writes require `allowWrites`;
   the target policy applies to every face.
3. **Swarm brain**: `do` steps of N testers call the project's own AI directly through `LlmClient` (ADR-0008),
   because an IDE agent cannot drive hundreds of parallel sessions over MCP.

Pətək never embeds another coding agent to read the target's source, and never stores or executes host instruction
files. Rules 1–7 of AGENTS.md are what keep the outcome independent of which AI is calling: the harness measures time,
code checks assertions, the AI only picks whitelisted actions.

## Options Considered
- **A. Skill pack only (pure BMAD).** No runtime, no evidence, no product to sell; contradicts the product goal.
- **B. Embed one vendor's coding agent as the root-cause engine.** Ties the product to one vendor; duplicates what the host AI
  already has (repository access, user trust).
- **C (chosen). Product with a tool surface + skill pack.** The host AI is a caller; Pətək owns UI, evidence, report.

## Consequences
- Easier: any MCP client gets explore → findings in one session; CI and scripts use `--json`; roles are documented
  once and reused by every AI.
- Harder: the tool surface is a public contract and needs versioning and contract tests; the MCP transport is a thin
  stdio JSON-RPC implementation of our own (decided 2026-09-26; no SDK dependency, rule 11).
- Role instructions are written in English (global use). Every text the AI writes for the owner (explorer, testers,
  triage) follows the owner's language (`PETEK_LANGUAGE`, default `auto`: the language of the owner's own text; the
  owner's decision 2026-09-26, nothing is forced to Azerbaijani); the panel's own labels stay Azerbaijani until it is
  localised.

## Action Items
1. [x] Tool surface over `PanelBackend` (`features/dashboard/infrastructure/mcp`, 25 tools; `findings` and `teardown`
   added to `PanelRuns`); [ ] `FindingBundle` as one object in the reporting domain (today: `get_findings` +
   `get_evidence`).
2. [x] `petek mcp` (thin stdio JSON-RPC, no SDK — decided 2026-09-26) and `--json` on `doctor`, `init`, `plan`, `run`,
   `report`, `teardown`; contract tests over byte streams (`McpServerTest`, `McpCommandTest`).
3. [x] `petek init` skill pack; role instructions (`.petek/SKILL.md`); MCP entry per agent.
