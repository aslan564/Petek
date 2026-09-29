# R11 — Universal target model: tenant-optional core, free-form roles, blind test patterns

**Status:** Implemented (Faza 13) · **ADRs:** 0010

## Requirement

Pətək must test sites that are not HR SaaS: no company, no departments, roles the site defines, no test API. When it
knows nothing about a site, it must still run useful "blind" tests derived from what the explorer saw.

The owner's decision of 2026-09-27: Pətək is built for no particular site, and nothing in it may say otherwise. No real
site, product, customer or company is named anywhere: code, tests, documents, examples, configuration and IDE run
configurations (AGENTS.md rule 13). Examples use neutral names (`staging.example.com`, `portal.example`, `*.test`, the
fake target's "Demo Portal"); a site's own settings live only in its owner's target profile (`targets/<name>.yaml`).

## Why

Before Faza 13 the domain model had the shape of one company HR application: roles were the enum
`ADMIN/MANAGER/EMPLOYEE`, every campaign carried
a company, departments and a registration quota, `seed_company` assumed a tenant, prompts added a "Company
context". The engine (browser, agent loop, assertions, realtime, explorer) was generic; the model around it was not.

## Architecture (Faza 13)

- Roles and registration modes become free-form strings defined by the campaign or target profile; `admin/manager/
  employee` are only the contract's values. Departments and the company become optional (`tenant: none | company`), the
  company logic (`seed_company`, invitation vs company code, the prompt block, per-company teardown) a plugin.
- Oracle resources and `/test/...` paths configurable per target; `none` is a first-class mode with evidence tiers.
- Blind patterns in `TestPatterns` that need no site knowledge: form validation (empty/long/invalid), double submit
  (idempotency), direct-URL permission checks across roles, races on one object, session expiry, back button, broken
  links, console/network errors, slow endpoints, mobile viewport. Each says which evidence tier it can deliver.
- Explorer drafts without a company setup (login-only or anonymous); seed paths and keywords move to the profile. A
  draft is a company draft only on the owner's word or when the explorer saw the site's own company way (Faza 25.1),
  never because a test API is there. Its frame keeps no contract values (Faza 25.2): the team comes from the owner's
  form or the roles the explorer saw (two managers only when a manager was seen), the departments from the owner's form
  or the department options the explorer saw (else one of the draft's own test company, `Test`), and oracle checks are
  written only for resources the trial touch saw the test API answer with (`TestApiProbe`: `GET /test/<resource>/
  latest?by=<the creator's e-mail>` carrying the trial's marker), not for a fixed `announcements`/`tickets` list.
- Defaults of any one site leave the core (`PetekConfig`, `.env.example`, panel placeholder); a site's settings live in
  its target profile (`docs/examples/target-profile.yaml`).

## Modules touched

`core/domain` (`Roles.kt`), `campaign` (`Campaign`, `CampaignSettings`, validator), `agent` (`PromptBuilder`, run
functions), `orchestration` (teardown), `explorer` (`TestPatterns`, `ScenarioComposer`, `ScenarioSettings`), `app`.

## Verification

- `TenantlessEndToEndTest` (real Chromium): a `tenant: none` campaign against `FakeNotesServer` (no companies, no test
  API) signs testers up, keeps a visitor anonymous, reports oracle checks as "N/A (no oracle)", passes `site_health`
  and `direct_url` on a correct site and finds the deliberate `FOREIGN_NOTE_VISIBLE` hole. Explorer drafts for such a
  site are covered by `GenerateScenarioUseCaseTest`; the company portal example and the panel e2e are unchanged.
- The universal success criterion (PLAN.md "Uğur meyarları", Faza 25.4) is checked in code: on a site unlike the
  contract (a recipe site with a test API but no companies) every main step of the draft belongs to an idea of an
  action the explorer saw or to the checks of pages it visited, every tester signs up through the form it found, and no
  company, invitation, code, announcement or ticket is assumed (`GenerateScenarioUseCaseTest`). The announcement,
  ticket and test-company criteria of the MVP are the contract site's e2e criteria.
- `ArchitectureTest` fails when a declaration of `az.petek.core` is named after an HR concept.
- The examples (`docs/examples/company-portal.yaml`, `company-portal-anonymous.yaml`, `target-profile.yaml`) are loaded
  by `CompanyPortalCampaignFileTest`, `LoadCampaignUseCaseTest`, `YamlTargetSpecSourceTest` and `PanelScenariosTest`,
  so the documented examples stay valid.

## Open items

- The riskiest refactor of the plan: done in slices, each slice keeping `./gradlew build` and `./gradlew e2eTest` green.
