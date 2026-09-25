# ADR-0004: Whitelisted agent actions, deterministic `run` steps, harness-held secrets

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
LLM agents are non-deterministic and can drift from the task. Registration of 29 testers is repetitive and must be
fast and stable. Test credentials and one-time codes must not be sent to an external model.

## Decision
- An agent may only perform the actions of the sealed `AgentAction` type: navigate, click, type, select, read_text,
  wait_text, get_email_code, get_phone_code, done, report_problem. The model sees a numbered element snapshot, never
  raw HTML.
- Deterministic flows are `run` functions in code (`register_owner`, `register_and_login` for invitation or company
  code, `seed_company`, `login`, `verify_identity`, `read_email_code`). `do` is used only where judgement is being
  tested (e.g. the admin's UI sign-up, creating an announcement).
- Secrets and codes stay in the harness. The model types placeholders (`{self.password}`, `{vars.email_code}`) and
  the harness substitutes them before typing.
- Loop detection (3 identical actions), step limits, invalid-decision limits and timeouts end a task with a typed
  `FailureReason`.

## Options Considered

### Option A: Free-form browser agent (the model gets full Playwright/HTML access)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | High tokens (full HTML) |
| Scalability | Poor reproducibility |
| Team familiarity | Medium |

**Pros:** flexible. **Cons:** unbounded actions, secrets exposed, flaky, expensive.

### Option B: Everything scripted (no LLM)
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | Zero tokens |
| Scalability | High |
| Team familiarity | High |

**Pros:** deterministic. **Cons:** tests nothing a scripted E2E suite would not already test, and cannot explore new UI.

### Option C (chosen): Whitelist + `run`/`do` split + placeholders
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | Tokens only where thinking is needed |
| Scalability | Good |
| Team familiarity | High |

**Pros:** bounded, auditable actions and reproducible setup. Passwords never reach the LLM.
**Cons:** new capabilities need code changes, by design. The `run` flows depend on the target's `data-testid` contract.

## Consequences
- Easier: reasoning about what an agent can do; evidence per action.
- Harder: `run` flows break if the target's markup diverges from docs/TARGET_CONTRACT.md (selectors are overridable per campaign).
- Revisit: the explorer phase (Faza 6) may need more tools. Add them as new `AgentAction` subtypes.

## Action Items
1. [x] Protocol, loop, run functions, placeholders.
2. [ ] Add `get_phone_code` (integration fixes, in progress).
3. [ ] Align KadroHR markup with docs/TARGET_CONTRACT.md, or override selectors in the campaign.
