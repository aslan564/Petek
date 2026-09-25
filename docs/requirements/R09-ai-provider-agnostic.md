# R09 — Works with whatever AI the host project uses; never tied to one vendor

**Status:** Partial (port and two Claude adapters implemented; registry, generic adapters and `auto` planned) · **Plan:** Faza 9 · **ADRs:** 0003, 0008

## Requirement

Pətək is a tool, not an AI. Whoever runs a project with Claude, Codex, Gemini, Copilot, Ollama or any other AI must be
able to use Pətək with that AI. No AI means no exploration and no `do` steps (for now), but frozen `run` scenarios
still execute. Claude is the default today because the owner's project uses it.

## Why

The owner's words: "bu AI məsələsi tək Claude-dan asılı olmamalıdır … BMAD hansısa AI-a bağlı deyil, hamısı onu
istifadə edə bilir". Vendor independence is also what keeps the model cost off Pətək (R15).

## Architecture (today)

- `LlmClient` port (`features/llm`): request = system + messages + JSON response schema; response = JSON + usage +
  optional cost. Decorators `ConcurrencyLimited` → `Retrying` → `Metered`. Two adapters: `ClaudeCliLlmClient`
  (`claude -p`, structured output, no tools, scrubbed environment) and `AnthropicApiLlmClient` (official SDK).
- An audit (2026-09-25) found no prompt relies on Claude behaviour: plain text, "answer with one JSON object", code
  validation of every answer (R02).

## Architecture (Faza 9, ADR-0008)

- `LlmProviderId` becomes an open key; `LlmProviders` is a registry map — adding a provider never edits a `when`.
- `CliAgentLlmClient`: the generic process machinery (scratch dir, timeout, kill tree, bounded output) with one small
  profile per CLI agent (command line, environment, transcript, parser): Claude first, then `codex exec`,
  `gemini -p`, `opencode run`. CLIs without a schema flag get the schema in the prompt; `StructuredJson` parses.
- `OpenAiCompatibleLlmClient` over the Ktor client already in the catalog: `/v1/chat/completions`,
  `response_format: json_schema` → `json_object` → prompt; a strict-schema adapter (all properties required, optional
  ones nullable). Covers OpenAI, Ollama, Groq, Mistral, OpenRouter, LM Studio and compat endpoints.
- `PETEK_LLM_PROVIDER=auto` (default): explicit `.env` → environment keys → host repository markers (`CLAUDE.md`,
  `AGENTS.md`, `GEMINI.md`, `.github/copilot-instructions.md` — markers only, never executed) → binaries on `PATH`.
  `doctor` says what it picked and why.
- Neutral config keys (`PETEK_LLM_BIN`, `PETEK_LLM_BASE_URL`, `PETEK_LLM_API_KEY`, `PETEK_LLM_STRUCTURED`,
  `PETEK_LLM_EFFORT`); old names as aliases; per-provider default model; provider-neutral key redaction.

## Modules and key types

`llm`: `LlmClient`, `LlmRequest`, `LlmResponse`, `LlmException`, `ClaudeCliLlmClient`, `ClaudeCliInvocation`,
`ClaudeCliResultParser`, `AnthropicApiLlmClient`, `StructuredJson`, decorators. `app`: `LlmProviders`,
`ConfigLoader`, `Doctor`.

## Verification

- Today: `ClaudeCliLlmClientTest` (arguments, environment, parsing, error mapping on a fake process),
  `ClaudeCliProcessTest` (real process), `AnthropicApiLlmClientTest` (Ktor fake), decorator tests, `AppContainerTest`.
- Faza 9: `CliAgentLlmClientTest` per profile, `OpenAiCompatibleLlmClientTest`, `LlmProviderResolverTest` with
  fixture directories; the contract demo on the fake target with Claude, Ollama and a fake `codex`.

## Open items

- MCP sampling (the host AI lends its model to the swarm) is a third option once client support is broad.
- Weaker models decide worse in `do` steps: the report names provider and model; `run` steps stay preferred.
