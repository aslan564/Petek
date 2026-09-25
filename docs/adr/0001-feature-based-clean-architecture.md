# ADR-0001: Feature-based clean architecture in Gradle modules

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Pətək grows in phases (MVP web → explorer agent → API/mobile adapters → web panel and multi-machine; docs/PLAN.md).
It is built by one developer, often with AI coding agents working in parallel. The code must stay easy to change per
capability (e.g. swap Mailpit for IMAP, Playwright for Appium, Claude CLI for another LLM) without ripple effects,
and boundaries must be enforceable rather than a matter of discipline.

## Decision
Every capability is a Gradle module under `features/<name>` (campaign, identity, evidence, mail, oracle, browser, llm,
agent, verification, orchestration, reporting). Each has three packages: `domain` (pure model and port interfaces),
`application` (use cases), `infrastructure` (adapters). A shared kernel lives in `core/domain` and `core/sqlite`.
`app` is the only composition root (manual constructor injection). Konsist tests in `e2e` enforce the layer rules.
Shared test doubles live in each module's `testFixtures`.

## Options Considered

### Option A: Single module, packages by layer
| Dimension | Assessment |
|---|---|
| Complexity | Low |
| Cost | Lowest build setup |
| Scalability | Poor: every change touches shared layer packages |
| Team familiarity | High |

**Pros:** fastest to start, one build file. **Cons:** no compile-time boundaries, features tangle, parallel agents conflict.

### Option B: Gradle modules per layer (`domain`, `application`, `infrastructure`)
| Dimension | Assessment |
|---|---|
| Complexity | Medium |
| Cost | Medium |
| Scalability | Medium: layers enforced, features still mixed |
| Team familiarity | Medium |

**Pros:** layering checked by the compiler. **Cons:** a feature change spans three modules; features can still couple.

### Option C (chosen): Gradle module per feature, layers as packages, architecture tests
| Dimension | Assessment |
|---|---|
| Complexity | Medium (16 modules, convention plugins hide the boilerplate) |
| Cost | Medium (build-logic, version catalog) |
| Scalability | High: a feature is replaced or extended in one place |
| Team familiarity | Medium |

**Pros:** feature isolation with compile-time dependencies, parallel work without conflicts, adapters swappable behind ports.
**Cons:** in-module layering is checked by tests, not by the compiler; more modules to wire in `app`.

## Trade-off Analysis
Option C gives module-level isolation where change actually happens (per capability) and leaves the finer
layer rule to Konsist. Option B would enforce layers more strictly, but at the cost of spreading every feature across modules.
Eleven features were implemented in parallel in separate worktrees and merged with zero conflicts. That is direct
evidence the boundaries work.

## Consequences
- Easier: adding an adapter (IMAP `Mailbox`, API/mobile `BrowserSession` equivalent, another `LlmClient`) is one new class plus wiring.
- Easier: unit tests use fakes from `testFixtures`; no mocking library.
- Harder: cross-feature contract changes need coordination (additive changes only while others build against them).
- Revisit: if a feature's infrastructure grows heavy (e.g. mobile), split it into its own module (`features/browser-mobile`).

## Action Items
1. [x] Convention plugins, version catalog, per-feature modules.
2. [x] Konsist architecture tests in `e2e` (`ArchitectureTest`, 7 rules, run with `build`; added 2026-09-25 — the
   rules had been stated in CLAUDE.md but not enforced until then).
3. [ ] Revisit module granularity at Faza 8 (web panel / Spring Boot).
