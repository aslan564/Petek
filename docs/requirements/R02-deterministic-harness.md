# R02 — Code decides: harness clock, code-evaluated assertions, action whitelist, `run` vs `do`

**Status:** Implemented · **Plan:** Faza 2, 4 · **ADRs:** 0003, 0004, 0007

## Requirement

The AI never measures time, never judges pass/fail and never performs an operation the code does not know. Anything
deterministic is executed by code (`run`); only what needs judgement is delegated to the model (`do`), one whitelisted
action at a time. The result of a run must be worth the same whatever AI produced the decisions.

## Why

This is what makes Pətək a test tool rather than a chat: verdicts are reproducible, auditable and independent of the
model's mood or vendor. It is also the precondition for bring-your-own-AI (R09).

## Architecture

- **Clock.** `HarnessClock` (`core/domain`) pairs a wall `Instant` with `System.nanoTime`; every t0/t1, step duration
  and latency comes from it (rule 1).
- **Assertions.** `features/verification` evaluates typed assertions in code: `visible_text`, `not_visible`, `count`,
  `latency_max`, `http_status`, `only_one_succeeds` (from each actor's own mutating requests) and `oracle` (rule 2).
- **Whitelist.** `features/agent` defines `AgentAction`; the model answers one structured JSON decision validated by
  `DecisionProtocol` against a JSON schema and the whitelist; anything else is rejected and retried, then failed
  (rule 3). New operations are code changes, never prompt changes.
- **`run` vs `do`.** `RunFunction`s (`register_owner`, `seed_company`, `register_and_login`, …) execute the target's
  flows as data (`TargetProfile.flows`, `FlowRunner`) with no model involved (rule 6); `do` steps go through
  `AgentLoop` with a numbered page snapshot and a loop detector.
- **Structured output.** `LlmClient` (`features/llm`) requires a response schema on every call; parsers validate in
  code, so a weaker or different model cannot widen what an agent may do.

## Modules and key types

`core/domain`: `HarnessClock`, `HarnessTimestamp`. `agent`: `AgentAction`, `DecisionProtocol`,
`JsonDecisionProtocol`, `DefaultAgentLoop`, `LoopDetector`, `RunFunction`, `RunFunctions`, `FlowRunner`.
`verification`: `AssertionEvaluator`, `DefaultAssertionEvaluator`, `RaceEvidence`, `RaceVerdict`. `campaign`:
`Flow`, `FlowStep`, `TargetProfile`.

## Verification

- `agent`: `JsonDecisionProtocolTest`, `DefaultAgentLoopTest` (rejected actions, loop detection, whitelist),
  `FlowRunnerTest` and run-function tests with `FakeBrowserSession` and `ScriptedLlmClient`.
- `verification`: `DefaultAssertionEvaluatorTest`, `RaceVerdictTest`.
- `core/domain`: clock tests with `FakeHarnessClock`.

## Open items

- Faza 9 adds a strict-schema variant for providers whose structured-output mode rejects optional properties; the
  in-code validation stays the judge (ADR-0008).
