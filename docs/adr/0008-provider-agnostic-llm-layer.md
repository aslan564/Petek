# ADR-0008: AI-provider-agnostic LLM layer with automatic detection

**Status:** Accepted (extends ADR-0003; amended 2026-09-26: no vendor is special, see R09)
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
ADR-0003 put every model call behind `LlmClient` with two adapters (one vendor's command-line agent and its API). The
owner now wants Pətək to be a tool that works with whatever AI the host project already uses (Codex, Gemini, Cursor,
Grok, Copilot, Ollama, ...), the way BMAD works with any AI IDE. Pətək must not carry the model cost or bind the product to one vendor.

An audit of the code (2026-09-25) found the coupling is narrow: no prompt relies on one vendor's behaviour (no XML
tags, thinking, prefill or native tool use; four call sites, each asking for one JSON object). The coupling lived in the
closed `LlmProviderId` enum, the exhaustive factory `when`, vendor-named config keys and defaults, vendor wording in
hints, and three JSON schemas with optional properties that OpenAI's strict mode rejects.

## Decision
- `LlmProviderId` becomes an open key; providers register in a factory map. Adding a provider never edits a `when`.
- A generic `CliAgentLlmClient` owns process handling (scratch dir, timeout, kill tree, bounded output). Each CLI agent
  is a small profile: command line, environment, transcript format, result parser: `codex exec`, `gemini -p`,
  `opencode run`, and (2026-09-26) a generic profile for any tool the owner describes in `.env`, which replaced the
  first vendor-specific one. CLIs without a schema flag get the schema in the prompt and
  `StructuredJson` parsing; code validation (`DecisionProtocol` and friends) stays the judge.
- One `OpenAiCompatibleLlmClient` over the Ktor client covers OpenAI, Ollama, Groq, Mistral, OpenRouter, LM Studio and
  the compat endpoints of Gemini and Anthropic. Structured output degrades `json_schema` → `json_object` → prompt.
  A strict-schema adapter lists every property as required and makes optional ones nullable.
- `PETEK_LLM_PROVIDER=auto` is the default. Resolution order: explicit `.env` → environment keys → markers in the
  host repository (`AGENTS.md`/`.codex`, `GEMINI.md`/`.gemini`, `.github/copilot-instructions.md`) → binaries on
  `PATH`; the other agent CLIs found are fallbacks, and nothing found is the `none` provider, never a vendor picked for
  the owner. Every step is logged with its reason and shown by `doctor`.
- Config keys are provider-neutral (`PETEK_LLM_BIN`, `PETEK_LLM_BASE_URL`, `PETEK_LLM_API_KEY`, `PETEK_LLM_STRUCTURED`,
  `PETEK_LLM_EFFORT`, `PETEK_LLM_ARGS`, `PETEK_LLM_ENV_UNSET`). No provider has a default model chosen by Pətək.
- Hosts read as instructions (`AGENTS.md` etc.) are markers only. Pətək never executes or obeys their content.

## Options Considered
- **A. Keep one vendor, add providers on demand.** Cheapest now; contradicts the product decision and keeps the
  vendor's naming in every hint and test.
- **B. Native SDK per provider (OpenAI, Gemini, ...).** Best per-vendor features; one new dependency per vendor
  (rule 11), most of which the OpenAI-compatible surface already covers.
- **C (chosen). Open registry + generic CLI adapter + one OpenAI-compatible HTTP adapter + auto detection.** Covers
  the long tail with two adapters, keeps agent/explorer/triage code untouched, and adds no dependency (Ktor client is
  already in the catalog).

## Consequences
- Easier: the same campaign runs on any CLI agent, on Ollama and on any OpenAI-compatible API; `doctor` explains what
  it picked.
- Harder: weaker models decide worse in `do` steps. The report names provider and model per run; frozen `run` steps
  stay the preferred form; the explorer turns learned flows into `run` steps wherever it can.
- Cost reporting stays optional (`costUsd` nullable): only some providers report it.
- The three schemas gain a strict variant; parsers must read `null` as absent.

## Action Items
1. [x] Registry, `CliAgentLlmClient`, profiles extracted, tests on the fake process runner; the generic profile
   (2026-09-26).
2. [x] `OpenAiCompatibleLlmClient` with the strict-schema adapter and a Ktor fake server test.
3. [x] `LlmProviderResolver` (`auto`) with fixture-directory tests; `doctor` output.
4. [x] Neutral config keys, hints, redactor patterns, docs, `.env.example`.
