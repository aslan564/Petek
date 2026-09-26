# R16 — Link-only swarm: a link in, a tested site out

**Status:** Planned (Faza 15 next) · **Plan:** Faza 15–22 · **ADRs:** 0012, 0007, 0010

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

- **Faza 15 — ownership and the permission gate** (planned): `features/ownership`, the proof file or DNS record, the
  exemption of loopback and private addresses, the gate on runs, teardown, panel, MCP and the explorer's writing phases.
- **Faza 16 — mail**: the owner's mailbox with plus addressing over IMAP.
- **Faza 17 — explorer**: site type, the explorer's own account, pass 0 → pass 1.
- **Faza 18 — gates, accounts, isolation**: gate assignment, gate learnt once and replayed by code, gate barrier, no
  colleague roster (card placeholders), account swaps on permission, exploration throughout the run.
- **Faza 19 — small-bug cards**: the general catalogue and the first three patterns per site type.
- **Faza 20 — report**: customer layer, detail layer, JUnit XML, SARIF.
- **Faza 21 — capacity**: waves; a distinct IP per tester on verified sites only.
- **Faza 22 — demo targets**: Ghost and WooCommerce on the owner's server.

## Modules and key types

To be filled per phase.

## Verification

To be filled per phase.

## Open items

- IMAP library (rule 11) before Faza 16.
