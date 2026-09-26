# Architecture Decision Records

| ADR | Title | Status |
|---|---|---|
| [0001](0001-feature-based-clean-architecture.md) | Feature-based clean architecture in Gradle modules | Accepted |
| [0002](0002-shared-browser-server.md) | One shared Chromium server, one confined Playwright per agent (amended: sharded servers, any number of agents, dialogs) | Accepted, amended |
| [0003](0003-llm-structured-decisions.md) | LLM decisions as structured output behind a provider port | Accepted |
| [0004](0004-agent-action-model.md) | Whitelisted agent actions, deterministic `run` steps, harness-held secrets | Accepted |
| [0005](0005-evidence-store.md) | SQLite evidence store with a single writer plus artifact files | Accepted |
| [0006](0006-realtime-coordination-and-latency.md) | In-process event bus; latency measured in the receiver's DOM | Accepted |
| [0007](0007-verdicts-and-target-safety.md) | Code-evaluated three-source verdicts and layered target safety | Accepted |
| [0008](0008-provider-agnostic-llm-layer.md) | AI-provider-agnostic LLM layer with automatic detection (extends 0003) | Accepted |
| [0009](0009-product-boundary-and-tool-surface.md) | Pətək is the product; host AI is a caller — MCP, `--json`, skill pack | Accepted |
| [0010](0010-target-profiles-and-sign-in-chain.md) | Target profiles, sign-in strategy chain and evidence tiers | Accepted |
| [0011](0011-open-core-edition-boundary.md) | Open-core edition boundary, workspace identity, opt-in telemetry (licence part superseded by 0013) | Accepted |
| [0012](0012-link-only-swarm.md) | Link-only swarm: verified ownership, a gate learnt once, isolated cards | Accepted |
| [0013](0013-apache-2-open-source.md) | Open source under Apache 2.0 now; DCO sign-off; maintainers merge into develop, the owner into main | Accepted |

Each ADR records the forces at the time of the decision. Supersede an ADR with a new one rather than rewriting it.
