# Architecture Decision Records

| ADR | Title | Status |
|---|---|---|
| [0001](0001-feature-based-clean-architecture.md) | Feature-based clean architecture in Gradle modules | Accepted |
| [0002](0002-shared-browser-server.md) | One shared Chromium server, one confined Playwright per agent | Accepted |
| [0003](0003-llm-structured-decisions.md) | LLM decisions as structured output behind a provider port | Accepted |
| [0004](0004-agent-action-model.md) | Whitelisted agent actions, deterministic `run` steps, harness-held secrets | Accepted |
| [0005](0005-evidence-store.md) | SQLite evidence store with a single writer plus artifact files | Accepted |
| [0006](0006-realtime-coordination-and-latency.md) | In-process event bus; latency measured in the receiver's DOM | Accepted |
| [0007](0007-verdicts-and-target-safety.md) | Code-evaluated three-source verdicts and layered target safety | Accepted |

Each ADR records the forces at the time of the decision. Supersede an ADR with a new one rather than rewriting it.
