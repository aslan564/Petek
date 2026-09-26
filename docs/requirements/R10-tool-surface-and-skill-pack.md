# R10 — Any host AI drives Pətək (MCP, `--json`, skill pack); root cause happens in the host's repository

**Status:** Skill pack, MCP server and `--json` done 2026-09-26 (`petek init`; `petek mcp` stdio server with 25 tools
over `PanelBackend`, read-only unless `--allow-writes`; `--json` on `doctor`, `init`, `plan`, `run`, `report`,
`teardown`); `FindingBundle` as one object and `petek dev` planned (Faza 11–12) · **ADRs:** 0009

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

- **Tool surface (Faza 11, done).** One set of use cases (`PanelBackend`) behind three faces: the web panel, the MCP
  server and `--json` on the CLI.
  - `petek mcp` (`app/cli/McpCommand` over `PanelCore`, the panel's object graph without its HTTP server) runs
    `features/dashboard/infrastructure/mcp/McpServer`: newline-delimited JSON-RPC 2.0 over stdio, hand-rolled (the
    stdio profile is four methods; no new dependency, rule 11): `initialize` (protocol 2024-11-05, 2025-03-26,
    2025-06-18; the client's version when supported), `notifications/*` ignored, `ping`, `tools/list`, `tools/call`.
    Requests run concurrently, responses are written one line at a time; stdout carries protocol only, logs go to
    stderr and the file. `McpTools` holds the 25 tools with JSON-Schema arguments; results are the panel's own JSON
    (`PanelJson`) as text plus `structuredContent`; a panel failure is an `isError` result with the Azerbaijani
    message, a protocol mistake a JSON-RPC error (-32700, -32600, -32601, -32602). `McpSettings.allowWrites`
    (`--allow-writes`) gates `cancel_exploration`, `approve_scenario`, `freeze_scenario`, `run_campaign`, `cancel_run`,
    `teardown` and `explore_site` with `allowWrites`; `explore_site` and `run_campaign` take `wait`. `PanelRuns` gained
    `findings(runId)` (the judged findings with A/B/C and artifact ids, whose artifacts `get_evidence` then resolves
    to absolute paths) and `teardown(runId)` (finished runs of the configured site only).
  - `petek --json <command>` (`CliSession.json`, `PetekSubcommand.emitJson`): one document on stdout for `doctor`
    (`ok`, `checks`), `init` (`changes`), `plan` (`identities`), `run` (`runs`, `exitCode`), `report` (`html`,
    `markdown`), `teardown` (`removed`, `failures`); a failure prints `{"error": ...}` and keeps the exit code.
    `capacity`, `probe`, `smoke` still print text (their JSON follows with the CI mode of Faza 12).
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

- **Language.** Whatever text the AI writes for the owner follows the owner's language (`WorkingLanguage`, core
  domain; `PETEK_LANGUAGE`, default `auto` = the language of the owner's instructions, task or scenario, the page's
  when there is none; or a named language): the explorer's purposes and questions (`PageAnalysisProtocol.system`),
  the testers' summaries and reported problems (`PromptBuilder`), the triage rationale (`TriageOptions.language`).
  An owner who drives Pətək in English through their own AI gets English back; nothing is forced to Azerbaijani.

## Modules touched

`dashboard` (or a `toolface` feature over `PanelBackend`), `reporting` (`FindingBundle`), `app/cli` (`--json`,
`mcp`, `init`, `dev`), `build-logic` (distribution, Faza 12).

## Verification

- Done: `ProjectInitializerTest` (empty project; owner's `CLAUDE.md` and `.gitignore` gain the fragment once and keep
  their text, a stale fragment is replaced in place, a third run writes nothing; `.env` kept, profile rewritten only
  with `--force`; detection from `.cursor` and `GEMINI.md`; `.mcp.json` keeps other servers and keys, Copilot's
  `servers` shape; a broken JSON file is left alone), `InitCommandTest` (no configuration loaded, `--dir`, unknown
  `--ai` refused).
- Done: `McpServerTest` (dashboard, on the demo backend over byte streams: handshake and protocol version choice,
  tool list with schemas and write markers, read tools' text and structured content, not-found as `isError`, write
  refusal without and success with `--allow-writes`, JSON-RPC error codes for bad JSON, unknown method, unknown tool,
  missing argument, wrong `jsonrpc`), `McpCommandTest` (app, production wiring: the owner's scenario file is listed
  through MCP; stdout carries protocol only), `--json` assertions in `DoctorCommandTest`, `InitCommandTest`,
  `TeardownCommandTest`.
- Planned: an MCP client (Claude Code `.mcp.json`) runs `explore_site` → `get_findings` on the fake target end to end.

## Open items

- Decided 2026-09-26: a thin stdio JSON-RPC implementation, no SDK (the profile is small; the tools' contract is
  Pətək's own). Revisit only if a client needs resources, prompts or the streamable HTTP transport.
