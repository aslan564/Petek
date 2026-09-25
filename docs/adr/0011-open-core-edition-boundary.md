# ADR-0011: Open-core edition boundary, workspace identity and opt-in telemetry

**Status:** Proposed (licence choice pending)
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
The owner wants to be able to earn from Pətək later. Decisions that are cheap now and expensive later: the licence,
a tenant/workspace identity in the ID system, where paid implementations live, and whether usage can be measured.
Bring-your-own-AI (ADR-0008) already keeps the model cost off Pətək.

## Decision
- **Open core.** The engine, panel, tool surface and skill pack are open. Paid capabilities live behind ports that
  the core defines and implements locally: `RunRepository` (SQLite | hosted store), `ReportStore` (files | shared
  URL), `Orchestrator`/`AgentScheduler` (single machine | distributed), `UsageSink` (local file | account). Paid
  implementations live in a separate repository; the core never imports them. A Konsist rule enforces the direction.
- **`workspace_id`** joins `run_id`, `agent_id`, `step_id`, `event_id`, `correlation_id` (rule 4). Locally it is
  always `local`; hosted editions set it per account.
- **Telemetry** is a port, opt-in, off by default, counters only (features used, provider kind, tester count), never
  content, never secrets. The local implementation writes a file the owner can read.
- **Licence**: to be chosen by the owner — Apache 2.0 (widest adoption) or BSL 1.1 converting to Apache 2.0 after
  four years (protects a hosted offering). Recommendation: BSL 1.1, given the hosted-swarm plan. The `LICENSE` file
  lands in Faza 8, before wider distribution.
- **Brand**: `petek` (Latin spelling) for GitHub organisation, domain, npm and Maven coordinates; package root stays
  `az.petek`.

## Options Considered
- **A. Fully open, sell services only.** Simplest; leaves hosted competition free to copy and offers no product income.
- **B. Closed source.** Kills the BMAD-style spread the tool needs to be adopted.
- **C (chosen). Open core with ports for paid editions.** Adoption from the open engine; income from scale, history,
  team features and hosting.

## Consequences
- Easier: paid modules plug in without touching the core; the report and panel are the sales material.
- Harder: every new persistence or scheduling feature must be designed as a port first; a licence change later would
  cost community trust, so the choice must be made before Faza 12 (distribution).
- Never: carry the model cost in the open product, or route customer data through Pətək servers by default.
