# R09 — Works with whatever AI the owner has; never tied to one vendor

**Status:** Implemented (Faza 9; vendor-neutral since 2026-09-26) · **Plan:** Faza 9 · **ADRs:** 0003, 0008

## Requirement

Pətək is a tool, not an AI. Whoever works with Codex, Gemini, Cursor, Grok, Copilot, Ollama or any other AI must be
able to use Pətək with that AI, and Pətək's code and documents name no vendor as the default or the special one. No AI
means no exploration and no `do` steps, but frozen `run` scenarios still execute, and Pətək says how to set one up.

## Why

The owner's words (2026-09-25, paraphrased): the AI question must not depend on a single AI vendor — "BMAD hansısa AI-a
bağlı deyil, hamısı onu istifadə edə bilir". And on 2026-09-26: "bu alət ... heç birinə bağlı olmamalıdır, bütün AI-lar
ilə işləyə bilməlidir", after a tester found a vendor-specific flag that a CLI update had removed. Vendor independence
is also what keeps the model cost off Pətək (R15).

## Architecture

- `LlmClient` port (`features/llm`): request = system + messages + JSON response schema; response = JSON + usage +
  optional cost. Decorators `ConcurrencyLimited` → `Retrying` → `Metered`; `LlmProviderKey` is an open key and
  `LlmProviders` a registry map, so adding a provider never edits a `when`.
- **Any AI command-line tool** (`cli`): `GenericCliProfile` runs the executable in `PETEK_LLM_BIN` with the argument
  template in `PETEK_LLM_ARGS` (split without a shell; placeholders `{model}`, `{effort}`, `{system}`, `{schema}`,
  `{schema_file}`), sends the conversation on STDIN and reads the answer from STDOUT: the JSON object, or a JSON result
  envelope (`structured_output`, or `result`/`response`/`output`/`text` holding the answer; `is_error`, `usage`,
  `total_cost_usd`), accepted only when it carries the schema's required fields. `PETEK_LLM_ENV_UNSET` removes
  variables from the tool's environment. Pətək's code holds no vendor-specific flag, so a tool's update cannot break it.
- **Known agent CLIs** (`codex-cli`, `gemini-cli`, `opencode-cli`): small profiles on the shared `CliAgentLlmClient`
  (scratch directory, timeout, process-tree kill, bounded output, no shell).
- **APIs:** `openai-compat` (`OpenAiCompatibleLlmClient`: OpenAI, Grok at x.ai, OpenRouter, Gemini's OpenAI endpoint,
  Ollama, LM Studio, vLLM, Groq, Mistral; `json_schema` → `json_object` → prompt) and `anthropic-api` (official SDK).
  Neither has a default model: `PETEK_LLM_MODEL` names it.
- **`PETEK_LLM_PROVIDER=auto`** (`LlmProviderResolver`): explicit value → settings and keys in the environment
  (`PETEK_LLM_BASE_URL`, `PETEK_LLM_BIN`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY`, `OPENROUTER_API_KEY`,
  `GEMINI_API_KEY`) → project markers (`AGENTS.md`/`.codex`, `GEMINI.md`/`.gemini`; Copilot's file asks for an
  endpoint) → known agent CLIs on `PATH`, then Ollama. The other agent CLIs found become fallbacks
  (`FallbackLlmClient`: an unavailable provider is passed over once, later calls start with the one that works).
  Nothing found is the `none` provider (`UnavailableLlmClient`), whose every call says how to set one up; Pətək never
  picks a vendor for the owner.
- `doctor` shows the provider, why it was chosen, the fallbacks, which one answered and which were passed over.

## Modules and key types

`llm`: `LlmClient`, `LlmProviderKey`, `LlmRequest`, `LlmResponse`, `LlmException`, `CliAgentLlmClient`,
`GenericCliProfile`, `CliArguments`, `CodexCliProfile`, `GeminiCliProfile`, `OpenCodeCliProfile`,
`OpenAiCompatibleLlmClient`, `AnthropicApiLlmClient`, `FallbackLlmClient`, `UnavailableLlmClient`, `StructuredJson`,
decorators. `app`: `LlmProviders`, `LlmProviderResolver`, `ConfigLoader`, `Doctor`.

## Verification

- `llm`: `GenericCliProfileTest` (template, placeholders, envelopes, error mapping, environment removal on a fake
  process), `CliArgumentsTest`, `CliAgentProfilesTest`, `FallbackLlmClientTest`, `OpenAiCompatibleLlmClientTest`,
  `AnthropicApiLlmClientTest`, decorator tests.
- `app`: `LlmProviderResolverTest` (order, fallbacks, Grok and OpenRouter keys, `none`), `ConfigLoaderTest`,
  `AppContainerTest` (every registered provider builds; fallbacks wrap the chosen one), `DoctorCommandTest`.

## Open items

- MCP sampling (the host AI lends its model to the swarm) is a further option once client support is broad.
- Weaker models decide worse in `do` steps: the report names provider and model; `run` steps stay preferred.
