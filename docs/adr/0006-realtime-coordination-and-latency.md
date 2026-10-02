# ADR-0006: In-process event bus; latency measured in the receiver's DOM

**Status:** Accepted; amended 2026-09-28 (Faza 24.10: t0 from the write, receivers watch ahead)
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Multi-user scenarios depend on ordering ("admin publishes, 29 employees read"). Delivery latency is a key result. The
owner does not want Pətək to depend on how the target implements real time ("whichever is right, I only give the
site"). The LLM must never measure time (rule 1).

## Decision
- `emits` / `wait_for` go through an `EventBus` port; the MVP adapter is in-process (retained events, suspending
  waits, monotonic sequence).
- t0 is when the change reached the target: the harness time the emitter's own page saw the answer to the request
  that made it (`emits.request`, else the action's first accepted mutating request, an upper bound when there were
  several). The publish time is kept apart. When the page showed no write, t0 is the publish and the delay is only
  known to lie between the publish and the start of the emitter's action.
- A receiver's t1 is when the text appeared in **that receiver's DOM**. As the emitting step begins, its receivers
  start watching their own pages for the text of their `visible_text` (Option D); the page times the text itself. A
  text visible when the watch began proves nothing (`stale_text`). A text naming the event's own object cannot be
  watched for in advance: it is checked right after the event arrives, before the receiver's own `do` action, and a
  text visible at the first look gives only an upper bound.
- `latency_max` judges the range the delay lies in, never a point that may be an under- or overstatement.
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

### Option C (chosen 2026-09-25, superseded by D): DOM observation right after the event, transport auto-detected
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | — |
| Scalability | Target-agnostic |
| Team familiarity | High |

**Pros:** measures what a user sees, works for any transport. **Cons:** t0 is taken after the object id is looked up
(e.g. an oracle GET), so latency can be understated by that lookup time. The bus is single-process.

The audit of Faza 24 found the understatement much larger than the id lookup: t0 came after the emitter's whole `do`,
including the LLM turns after the submit, and receivers began looking only after the emitting step (and any step in
between) had finished. A delivery slower than the limit but faster than that tail measured close to 0 ms and
`latency_max` passed; the same text published again (a later wave, the account swap) was "seen" at once.

### Option D (chosen 2026-09-28): t0 from the write, receivers watch before it
| Dimension | Assessment |
|---|---|
| Complexity | Medium-high: a page-side watch, the emitter's requests read, latency as a range |
| Cost | One watch per receiver while the emitting step runs (DOM changes plus a 50 ms poll) |
| Scalability | Target-agnostic, like C; nothing is asked of the target |
| Team familiarity | Medium |

**Pros:** t0 and t1 are the moments the change was written and shown, so neither the emitter's nor the receiver's
agent time enters the delay, in either direction; stale text is detected instead of passing. **Cons:** a write the
emitter's page does not see (an API on another origin, a WebSocket message) leaves only a range; a text that names the
new object cannot be watched for in advance; a watch ends when its page navigates.

## Consequences
- Easier: the same campaign works on any target; the report shows the transport the target really uses; a latency
  result says whether it is exact or only a bound.
- Harder: multi-machine runs need a Redis/NATS adapter for `EventBus` (Faza 8); a campaign that wants an exact t0 on a
  page that sends several writes names the one with `emits.request` (the explorer writes it from the form it saw).
- Revisit: the mutating requests of an API on another origin are not recorded yet, so such sites get a range only.
