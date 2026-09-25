# R11 — Universal target model: tenant-optional core, free-form roles, blind test patterns

**Status:** Planned (Faza 13) · **ADRs:** 0010

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

## Verification (planned)

- A second fake site without a company concept runs explore → draft → run → report end to end; the KadroHR campaign
  produces unchanged results; a Konsist rule keeps HR concepts out of `core/domain`.

## Open items

- The riskiest refactor of the plan: done in slices, each slice keeping `./gradlew build` and `:e2e:e2eTest` green.
