# ADR-0007: Code-evaluated three-source verdicts and layered target safety

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Verdicts must be trustworthy and explainable: no verdict without evidence (rule 5), and assertions are checked by
code, never by the LLM (rule 2). Tests create companies and users on a real site, so a mistake must never harm
production data (rule 8). The owner wants to test kadrohr.com, which is a production host.

## Decision
- **Verdicts.** Typed assertions (`visible_text`, `not_visible`, `oracle`, `http_status`, `count`, `latency_max`,
  `only_one_succeeds`) are evaluated in code and recorded with artifacts. The judge compares three sources:
  A = what the sender did, B = what receivers saw, C = what the target's test API says.
  - A = B = C: pass.
  - C fails: backend finding.
  - C passes, B fails: delivery/UI finding.
  - Otherwise: "investigate".
  - An expected `permission_denied` refusal is a pass, and so is a lost race (`lost_race`): `only_one_succeeds`
    decides who won from each actor's own requests and the target's answers, never from the agent's report.
  - Which step is a permission test is decided by code too: a main step whose assertions check the refusal itself
    (`not_visible` of the control, `http_status` 401/403). In such a step an agent that reported a problem or gave up
    (`report_problem` of any kind, `done` with success=false) is recorded as the `permission_denied` refusal, its own
    words kept, and the assertions decide. Guard stops and errors (timeout, step limit, loop, browser, LLM) stay
    failures. Found in the first real run of 2026-09-26: the agent called an already-approved ticket "a problem" and
    failed a step whose 403 assertion had passed.
- **Target safety, in layers:**
  1. `TargetPolicy` refuses hosts listed in `PETEK_PRODUCTION_HOSTS` unless `PETEK_ALLOW_PRODUCTION=true`.
  2. The target's test API requires `X-Test-Token` and exists only in staging test mode.
  3. Test companies are flagged `is_test`: the oracle refuses to delete anything that is not, and the target refuses too.
  4. Teardown runs in `finally`, and `petek teardown --run <id>` covers crashed runs.

## Options Considered

### Option A: LLM judge (screenshot "does it look right?")
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | Tokens per verdict |
| Scalability | Non-deterministic |
| Team familiarity | Medium |

**Pros:** flexible. **Cons:** not reproducible and not auditable. Planned only as an additional signal in Faza 8.

### Option B (chosen): Typed assertions + three-source judge + layered safety
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | None at verdict time |
| Scalability | Deterministic |
| Team familiarity | High |

**Pros:** reproducible, explainable findings with evidence, and defence in depth for production.
**Cons:** full value needs the target's test API; without it, oracle checks are `SKIPPED` and the judge falls back to A/B.

## Consequences
- Easier: a failed check points to a class of bug (backend vs delivery/UI) with screenshots and oracle bodies.
- Harder: testing production (kadrohr.com) needs an explicit opt-in. Production has no test API, so e-mail/OTP
  sign-up flows and oracle checks cannot complete there. Recommended: a staging host with test mode.
- Revisit: when KadroHR ships test mode, run `petek probe` to verify the contract before the first real campaign.
