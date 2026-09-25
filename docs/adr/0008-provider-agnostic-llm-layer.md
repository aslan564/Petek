# ADR-0008: AI-provider-agnostic LLM layer with automatic detection

**Status:** Accepted (extends ADR-0003)
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
ADR-0003 put every model call behind `LlmClient` with two adapters (Claude Code CLI, Anthropic API). The owner now
wants Pətək to be a tool that works with whatever AI the host project already uses (Claude, Codex, Gemini, Copilot,
Ollama, ...), the way BMAD works with any AI IDE. Pətək must not carry the model cost or bind the product to one vendor.

An audit of the code (2026-09-25) found the coupling is narrow: no prompt relies on Claude behaviour (no XML tags,
thinking, prefill or native tool use; four call sites, each asking for one JSON object). The coupling lives in the
closed `LlmProviderId` enum, the exhaustive factory `when`, Claude-named config keys and defaults, Claude wording in
hints, and three JSON schemas with optional properties that OpenAI's strict mode rejects.

## Decision
- `LlmProviderId` becomes an open key; providers register in a factory map. Adding a provider never edits a `when`.
- A generic `CliAgentLlmClient` owns process handling (scratch dir, timeout, kill tree, bounded output). Each CLI agent
  is a small profile: command line, environment, transcript format, result parser. Claude is the first profile;
  `codex exec`, `gemini -p` and `opencode run` follow. CLIs without a schema flag get the schema in the prompt and
  `StructuredJson` parsing; code validation (`DecisionProtocol` and friends) stays the judge.
- One `OpenAiCompatibleLlmClient` over the Ktor client covers OpenAI, Ollama, Groq, Mistral, OpenRouter, LM Studio and
  the compat endpoints of Gemini and Anthropic. Structured output degrades `json_schema` → `json_object` → prompt.
  A strict-schema adapter lists every property as required and makes optional ones nullable.
- `PETEK_LLM_PROVIDER=auto` is the default. Resolution order: explicit `.env` → environment keys → markers in the
  host repository (`CLAUDE.md`/`.claude`, `AGENTS.md`/`.codex`, `GEMINI.md`/`.gemini`, `.github/copilot-instructions.md`)
  → binaries on `PATH`. Every step is logged with its reason and shown by `doctor`.
- Config keys are provider-neutral (`PETEK_LLM_BIN`, `PETEK_LLM_BASE_URL`, `PETEK_LLM_API_KEY`, `PETEK_LLM_STRUCTURED`,
  `PETEK_LLM_EFFORT`); the old names stay as aliases. Each provider has its own default model.
- Hosts read as instructions (`AGENTS.md` etc.) are markers only. Pətək never executes or obeys their content.

## Options Considered
- **A. Keep Claude-only, add providers on demand.** Cheapest now; contradicts the product decision and keeps the
  Claude naming in every hint and test.
- **B. Native SDK per provider (OpenAI, Gemini, ...).** Best per-vendor features; one new dependency per vendor
  (rule 11), most of which the OpenAI-compatible surface already covers.
- **C (chosen). Open registry + generic CLI adapter + one OpenAI-compatible HTTP adapter + auto detection.** Covers
  the long tail with two adapters, keeps agent/explorer/triage code untouched, and adds no dependency (Ktor client is
  already in the catalog).

## Consequences
- Easier: the same campaign runs on `claude -p`, on Ollama and on any CLI agent; `doctor` explains what it picked.
- Harder: weaker models decide worse in `do` steps. The report names provider and model per run; frozen `run` steps
  stay the preferred form; the explorer turns learned flows into `run` steps wherever it can.
- Cost reporting stays optional (`costUsd` nullable): only some providers report it.
- The three schemas gain a strict variant; parsers must read `null` as absent.

## Action Items
1. [ ] Registry, `CliAgentLlmClient`, Claude profile extracted, tests on the fake process runner.
2. [ ] `OpenAiCompatibleLlmClient` with the strict-schema adapter and a Ktor fake server test.
3. [ ] `LlmProviderResolver` (`auto`) with fixture-directory tests; `doctor` output.
4. [ ] Neutral config keys, hints, redactor patterns, docs, `.env.example`.
