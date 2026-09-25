# ADR-0006: In-process event bus; latency measured in the receiver's DOM

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Multi-user scenarios depend on ordering ("admin publishes, 29 employees read"). Delivery latency is a key result. The
owner does not want Pətək to depend on how the target implements real time ("whichever is right, I only give the
site"). The LLM must never measure time (rule 1).

## Decision
- `emits` / `wait_for` go through an `EventBus` port; the MVP adapter is in-process (retained events, suspending
  waits, monotonic sequence). t0 is the harness time when the event is published.
- A receiver's t1 is the harness time when `waitForText` sees the text in **that receiver's DOM**. Its
  `visible_text` / `latency_max` assertions are evaluated right after the event arrives, before the receiver's own
  `do` action, so LLM thinking time is not counted as delivery latency.
- The transport (WebSocket, SSE, polling) is detected from network traffic per session and reported, not configured.

## Options Considered

### Option A: Ask the target to report delivery times
| Dimension | Assessment |
|---|---|
| Complexity | Low for Pətək |
| Cost | Work on every target |
| Scalability | Target-specific |
| Team familiarity | Medium |

**Pros:** precise server-side numbers. **Cons:** measures the server, not what users see; not target-agnostic.

### Option B: Measure after the receiver's action
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | — |
| Scalability | — |
| Team familiarity | High |

**Pros:** matches step order in the YAML literally. **Cons:** latency includes LLM time, so `latency_max: 5000` could never pass.

### Option C (chosen): DOM observation right after the event, transport auto-detected
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | — |
| Scalability | Target-agnostic |
| Team familiarity | High |

**Pros:** measures what a user sees, works for any transport. **Cons:** t0 is taken after the object id is looked up
(e.g. an oracle GET), so latency can be understated by that lookup time. The bus is single-process.

## Consequences
- Easier: the same campaign works on any target; the report shows the transport the target really uses.
- Harder: multi-machine runs need a Redis/NATS adapter for `EventBus` (Faza 8).
- Revisit: stamp t0 before the id lookup if the understatement matters. It is documented in the orchestration code.
