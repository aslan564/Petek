# R16 — Link-only swarm: a link in, a tested site out

**Status:** Partial — Faza 15, 16, 20, 21 done; 17–19 largely done (19: the catalogue, showcase and news cards; the shop cards wait for Faza 22, see `docs/PLAN.md`); 22 open (owner's servers) · **Plan:** Faza 15–22 · **ADRs:** 0012, 0007, 0010

## Requirement

Given a link to a pre or stage site, and optionally an instruction document, Pətək finds big crashes and small bugs by
itself: an explorer classifies the site and learns its gate and its inside, every tester gets in through the gate the
scenario assigns (register, login with the document's test accounts, or guest), each gets a mission of its own, and the
report tells the customer in simple words and the developer in detail what broke, on which of three shelves (site bug,
tool gap, scenario error). Pətək writes only to a site whose ownership is verified; anywhere else it only reads.

## Why

The owner's decisions of 2026-09-26 (`docs/LINK_ONLY_SWARM.md`, section 0): anyone with a link should be able to test
their own site without writing a scenario, while the tool can never be aimed at somebody else's site, never shares an
account between concurrent testers, and never lets testers see each other.

## Architecture (by phase)

- **Faza 15 — ownership and the permission gate** (done): `features/ownership`, the proof file or DNS record, the
  exemption of loopback and private addresses, the gate on runs (CLI, panel, MCP) and the explorer's writing phases.
  Teardown is deliberately outside the gate (ADR-0012): it only deletes what a run itself created on an `is_test` company.
- **Faza 16 — mail**: the owner's mailbox with plus addressing over IMAP.
- **Faza 17 — explorer**: site type, the explorer's own account, pass 0 → pass 1.
- **Faza 18 — gates, accounts, isolation**: gate assignment, gate learnt once and replayed by code, gate barrier, no
  colleague roster (card placeholders), account swaps on permission, exploration throughout the run.
- **Faza 19 — small-bug cards**: the general catalogue and the first three patterns per site type.
- **Faza 20 — report**: customer layer, detail layer, JUnit XML, SARIF.
- **Faza 21 — capacity**: waves; a distinct IP per tester on verified sites only.
- **Faza 22 — demo targets**: an open-source news platform and an open-source shop on the owner's server.

## Modules and key types

- Faza 19: `explorer` `SmallBugCard`, `SmallBugCatalog` (the cards, each tied to the patterns that check it or saying
  why code does not yet), `Drafts`, `TestPattern.LANGUAGE_MIRRORS`, `TestPattern.EMPTY_LISTS`, `PageModel.lists`;
  `agent` `PageChecksRunFunction` (`mirrors`, `lists`); `browser` `PageFacts.alternates`; `core` `PathSegments`.

## Verification

- Faza 19: `GenerateScenarioUseCaseTest` (a showcase and a news draft name every card of their kind with its state; a
  public object is no leak, a draft is; a comment form counts its own items), `PageChecksRunFunctionTest` (language
  versions, lists), `ExploreSiteUseCaseTest` (the visitor's lists), `TestPatternLibraryTest`,
  `PlaywrightBrowserSessionTest` (`hreflang` read in Chromium).

## Open items

- IMAP library (rule 11) before Faza 16.
