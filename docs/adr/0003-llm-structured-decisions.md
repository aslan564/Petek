# ADR-0003: LLM decisions as structured output behind a provider port

**Status:** Accepted (amended 2026-09-26: the adapters are vendor-neutral, see ADR-0008 and R09)
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Agents need an LLM for `do` steps. The owner wants to use the AI subscription they already have (through its command-line
tool) now, and any other AI provider later. Each decision must be one validated action from a code-owned whitelist
(AGENTS.md rule 3). Up to 30 agents call the model concurrently, so cost, rate limits and failures need central
handling.

## Decision
Define an `LlmClient` port with a single operation: a request (system, messages, JSON schema) in, a JSON object plus
usage out. Providers are infrastructure adapters:
- A command-line AI agent run headless, one process per decision, with the user's own login: started via
  `ProcessBuilder` with an argument list (no shell), in an empty temporary directory, with a filtered environment.
  Since 2026-09-26 any such tool is described by the owner's configuration (`PETEK_LLM_BIN`, `PETEK_LLM_ARGS`); the
  code names no vendor.
- API adapters with an API key (an official Java SDK; later any OpenAI-compatible endpoint).

Cross-cutting behaviour is added by decorators: `ConcurrencyLimited` → `Retrying` → `Metered`. The agent validates
every answer with `DecisionProtocol` regardless of provider.

## Options Considered

### Option A: Native tool-use loop on a vendor's messages API
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | API billing only (not the owner's subscription) |
| Scalability | Good |
| Team familiarity | Medium |

**Pros:** first-class tool calling. **Cons:** cannot use the owner's subscription; provider-specific message shapes
leak into the agent.

### Option B: A vendor's agent SDK / full agent harness
| Dimension | Assessment |
|---|---|
| Complexity | Low for the loop, high for control |
| Cost | Plan or API |
| Scalability | Medium |
| Team familiarity | Low |

**Pros:** batteries included. **Cons:** built-in tools (bash, files) contradict the whitelist rule; no Kotlin SDK;
ties Pətək to one vendor.

### Option C (chosen): One structured-output decision per step behind `LlmClient`
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | Plan via a CLI, or API |
| Scalability | Bounded by the concurrency limit and the plan's rate limits |
| Team familiarity | High |

**Pros:** provider-neutral, and the whitelist lives in code. It works with a plan and with an API, and adding a
provider is one class. **Cons:** a CLI process per decision (~1–3 s overhead). Error classification depends on CLI
wording. A plan's limits carry no retry-after.

## Trade-off Analysis
The owner's constraint (use the subscription they have) rules out Option A as the default. Option B's power is exactly
what rule 3 forbids. Option C keeps the agent independent of the provider and makes every provider a configuration
switch.

## Consequences
- Easier: switching model/provider via `.env` (`PETEK_LLM_PROVIDER`, `PETEK_LLM_MODEL`); adding another AI later.
- Harder: the CLI must be logged in to an account that may use the model; `petek doctor` shows the tool's own error.
- Revisit: measure tokens per `do` step and tune the model and the effort level.

## Action Items
1. [x] Providers, decorators, error mapping, tests with a fake process and an embedded server.
2. [x] `petek doctor` checks provider usability and prints the exact provider error.
