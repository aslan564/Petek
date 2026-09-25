# ADR-0003: LLM decisions as structured output behind a provider port

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Agents need an LLM for `do` steps. The owner wants to use his existing Claude plan now and possibly other AI providers
later. Each decision must be one validated action from a code-owned whitelist (CLAUDE.md rule 3). Up to 30 agents
call the model concurrently, so cost, rate limits and failures need central handling.

## Decision
Define an `LlmClient` port with a single operation: a request (system, messages, JSON schema) in, a JSON object plus
usage out. Providers are infrastructure adapters:
- `ClaudeCliLlmClient` (default) runs `claude -p` headless with `--json-schema` structured output, no tools, no MCP
  and no settings, using the user's Claude plan login. It is started via `ProcessBuilder` with an argument list (no
  shell) and a scrubbed environment.
- `AnthropicApiLlmClient` uses the official Java SDK with an API key.

Cross-cutting behaviour is added by decorators: `ConcurrencyLimited` → `Retrying` → `Metered`. The agent validates
every answer with `DecisionProtocol` regardless of provider.

## Options Considered

### Option A: Native tool-use loop on the Messages API
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | API billing only (not the Claude plan) |
| Scalability | Good |
| Team familiarity | Medium |

**Pros:** first-class tool calling. **Cons:** cannot use the Claude plan; provider-specific message shapes leak into the agent.

### Option B: Claude Agent SDK / full agent harness
| Dimension | Assessment |
|---|---|
| Complexity | Low for the loop, high for control |
| Cost | Plan or API |
| Scalability | Medium |
| Team familiarity | Low |

**Pros:** batteries included. **Cons:** built-in tools (bash, files) contradict the whitelist rule; no Kotlin SDK.

### Option C (chosen): One structured-output decision per step behind `LlmClient`
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | Plan via CLI, or API |
| Scalability | Bounded by the concurrency limit and the plan's rate limits |
| Team familiarity | High |

**Pros:** provider-neutral, and the whitelist lives in code. It works with the plan and with the API, and adding a provider is one class.
**Cons:** a CLI process per decision (~1–3 s overhead). Error classification depends on CLI wording. The plan's limits carry no retry-after.

## Trade-off Analysis
The owner's constraint (use the current Claude plan) rules out Option A as the default. Option B's power is exactly
what rule 3 forbids. Option C keeps the agent independent of the provider and makes the API path a configuration switch.

## Consequences
- Easier: switching model/provider via `.env` (`PETEK_LLM_PROVIDER`, `PETEK_LLM_MODEL`); adding another AI later.
- Harder: the CLI login must be the Claude plan account. Today `claude auth status` shows a login without a plan
  (`subscriptionType: null`), and calls fail with "Credit balance is too low" until the owner runs `/login`.
- Revisit: on the CLI, Haiku 4.5 always runs with a 32k thinking budget. The default is `claude-sonnet-5` with
  effort `low`; measure tokens per `do` step in Faza 2 and tune.

## Action Items
1. [x] Both providers, decorators, error mapping, tests with fake process and embedded server.
2. [ ] `petek doctor` checks provider usability and prints the exact provider error.
3. [ ] Owner: `claude` → `/login` with the Claude plan account.
