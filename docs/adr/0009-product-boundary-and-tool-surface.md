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
1. **Host AI** (Claude Code, Codex, Gemini CLI, Cursor, ...) reads Pətək's skill pack (`SKILL.md`, `AGENTS.md`,
   `CLAUDE.md` fragment, `.cursor/rules`, `GEMINI.md`) and plays roles: explorer, scenario author, judge, root-cause.
   It calls Pətək through the tool surface. Root cause happens here: the host AI already has the repository open;
   Pətək hands it a `FindingBundle` (finding, step, request/response, screenshot, A/B/C, evidence tier).
2. **Pətək engine + panel** exposes one set of use cases (`PanelBackend` today) through three faces: the web panel,
   an MCP server (`petek mcp`, stdio, loopback only) and `--json` on every CLI command. Writes require `allowWrites`;
   the target policy applies to every face.
3. **Swarm brain**: `do` steps of N testers call the project's own AI directly through `LlmClient` (ADR-0008),
   because an IDE agent cannot drive hundreds of parallel sessions over MCP.

Pətək never embeds another coding agent to read the target's source, and never stores or executes host instruction
files. Rules 1–7 of CLAUDE.md are what keep the outcome independent of which AI is calling: the harness measures time,
code checks assertions, the AI only picks whitelisted actions.

## Options Considered
- **A. Skill pack only (pure BMAD).** No runtime, no evidence, no product to sell; contradicts the product goal.
- **B. Embed Claude Code as the root-cause engine.** Ties the product to one vendor; duplicates what the host AI
  already has (repository access, user trust).
- **C (chosen). Product with a tool surface + skill pack.** The host AI is a caller; Pətək owns UI, evidence, report.

## Consequences
- Easier: any MCP client gets explore → findings in one session; CI and scripts use `--json`; roles are documented
  once and reused by every AI.
- Harder: the tool surface is a public contract and needs versioning and contract tests; the MCP transport needs
  either the Kotlin MCP SDK (new dependency, rule 11) or a thin stdio JSON-RPC implementation — decision pending.
- Role instructions are written in English (global use); the panel stays Azerbaijani.

## Action Items
1. [ ] Tool surface module over `PanelBackend`; `FindingBundle` in the reporting domain.
2. [ ] `petek mcp` and `--json`; contract tests over stdio.
3. [ ] `petek init` skill pack; role instructions; `.mcp.json` entry.
