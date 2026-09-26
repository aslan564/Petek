# R11 — Universal target model: tenant-optional core, free-form roles, blind test patterns

**Status:** Implemented (Faza 13) · **ADRs:** 0010

## Requirement

Pətək must test sites that are not HR SaaS: no company, no departments, roles the site defines, no test API. When it
knows nothing about a site, it must still run useful "blind" tests derived from what the explorer saw.

## Why

Today the domain model has the shape of KadroHR: roles are the enum `ADMIN/MANAGER/EMPLOYEE`, every campaign carries
a company, departments and a registration quota, `seed_company` assumes a tenant, prompts add a "Company context".
The engine (browser, agent loop, assertions, realtime, explorer) is generic; the model around it is not.

## Architecture (Faza 13)

- Roles and registration modes become free-form strings defined by the campaign or target profile; `admin/manager/
  employee` are KadroHR's values. Departments and the company become optional (`tenant: none | company`), the
  company logic (`seed_company`, invitation vs company code, the prompt block, per-company teardown) a plugin.
- Oracle resources and `/test/...` paths configurable per target; `none` is a first-class mode with evidence tiers.
- Blind patterns in `TestPatterns` that need no site knowledge: form validation (empty/long/invalid), double submit
  (idempotency), direct-URL permission checks across roles, races on one object, session expiry, back button, broken
  links, console/network errors, slow endpoints, mobile viewport. Each says which evidence tier it can deliver.
- Explorer drafts without a company setup (login-only or anonymous); seed paths and keywords move to the profile.
- KadroHR defaults leave the core (`PetekConfig`, `.env.example`, panel placeholder) for `targets/kadrohr.yaml`.

## Modules touched

`core/domain` (`Roles.kt`), `campaign` (`Campaign`, `CampaignSettings`, validator), `agent` (`PromptBuilder`, run
functions), `orchestration` (teardown), `explorer` (`TestPatterns`, `ScenarioComposer`, `ScenarioSettings`), `app`.

## Verification

- `TenantlessEndToEndTest` (real Chromium): a `tenant: none` campaign against `FakeNotesServer` (no companies, no test
  API) signs testers up, keeps a visitor anonymous, reports oracle checks as "N/A (no oracle)", passes `site_health`
  and `direct_url` on a correct site and finds the deliberate `FOREIGN_NOTE_VISIBLE` hole. Explorer drafts for such a
  site are covered by `GenerateScenarioUseCaseTest`; the KadroHR campaign files and the panel e2e are unchanged.
- `ArchitectureTest` fails when a declaration of `az.petek.core` is named after an HR concept.

## Open items

- The riskiest refactor of the plan: done in slices, each slice keeping `./gradlew build` and `./gradlew e2eTest` green.
