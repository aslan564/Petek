# ADR-0011: Open-core edition boundary, workspace identity and opt-in telemetry

**Status:** Accepted (licence chosen 2026-09-25: BSL 1.1, Licensor Kodcraft, Author Aslan Aslanov, Change Date
2030-09-25 → Apache 2.0; `LICENSE`, `NOTICE` and enforced file headers are in `develop`)
**Date:** 2026-09-25
**Deciders:** Aslan Aslanov (owner, Kodcraft)

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
- **Ports in the code (2026-09-26):** `RunRepository` (evidence domain, SQLite), `ReportStore` (reporting domain,
  `ReportStore.LOCAL` keeps the report in the run's directory), `CampaignRunner` (orchestration application: the
  Orchestrator/AgentScheduler port; the default schedules every agent on this machine), `UsageSink`
  (`core/telemetry`, `PETEK_TELEMETRY=off|local`, `LocalFileUsageSink`). `WorkspaceId` is on `run`, `identity` and
  `finding` rows and on the report model; older databases gain the column with `local`
  (`SqliteDatabase.createMissing`). Konsist rule: no file imports `az.petek.premium|enterprise|hosted|cloud`.
- **Licence**: Business Source License 1.1 (owner's decision, 2026-09-25). Licensor Kodcraft (Aslan Aslanov);
  Additional Use Grant: production use to test software you own or operate, no Competing Offering; Change Date
  2030-09-25; Change License Apache 2.0. Every source file carries the header from `PetekLicense.kt`, enforced by
  Spotless in `build`; contributions are licensed to Kodcraft with the right to relicense (`CONTRIBUTING.md`).
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
