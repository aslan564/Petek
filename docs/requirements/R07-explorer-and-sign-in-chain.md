# R07 — The explorer learns an unknown site, drafts scenarios and gets in by the best available means

**Status:** Partial (exploration, drafts, triage implemented; sign-in chain planned) · **Plan:** Faza 6, 7, 10 · **ADRs:** 0009, 0010

## Requirement

Given only a URL and plain-language instructions, Pətək explores the site, builds a model of it, asks the owner what
it cannot infer, proposes test scenarios for review, and later turns a run's surprises into scenario improvements.
When the site needs a login, the explorer must get in: through the target's test API if it has one, else with
accounts the owner provides, else by registering itself and reading the OTP — falling back along that chain — and
only then stay anonymous.

## Why

Sites differ; the owner should not have to hand-write every scenario. The owner's own words (2026-09-25): the explorer
must be able to register, read the OTP, and fall back to provided credentials if registration fails.

## Architecture (today)

- `ExploreSiteUseCase` runs three phases under a page/time budget: `ANONYMOUS` (read-only session, crawl under
  `LinkPolicy`/`RobotsRules`), `ROLE_BASED` (logged-in sessions per role), `TRIAL_TOUCH` (harmless submits, only with
  permission and a confirmed test target). Code reads the page (`HtmlScanner`, `FormClassifier`, `Keywords`); the AI
  answers one structured question per page (`PageAnalyst`).
- `SiteModel` with `Provenance` (observed/inferred), versioned per target, event-logged; `CompareExplorationsUseCase`
  diffs versions. `TestPatterns` derive ideas; `GenerateScenarioUseCase` drafts a campaign the validator accepts.
- Logged-in sessions come from `TestCompanyRoleSessions` (app): a setup-only campaign creates a test company through
  the test API with the site's own target profile from the scenario catalog (`CatalogSetupProfiles`, Faza 8).
- Unknowns are questions; answers live in `AnswerBook` and ground later explorations and drafts.
- Triage (`features/scenarios`) classifies surprises and proposes scenario v2 as a diff.

## Architecture (Faza 10, ADR-0010)

- `SignInStrategy` port and an ordered chain: `test_company` → `own_accounts` → `self_register` → `anonymous`; every
  attempt and fallback becomes an event and a report line; the explorer and the testers share it.
- Owner accounts entered in the panel, stored as `.env` references (secrets never in the database); saved
  `storage_state` per (target, identity) reused across explorations.
- Mail sources `imap` and `manual` (the panel asks the owner for a code).
- Capability probe before exploration; evidence tiers on findings.

## Modules and key types

`explorer`: `ExploreSiteUseCase`, `CrawlPass`, `TrialToucher`, `PageAnalyst`, `SiteModel`, `SiteModelAccumulator`,
`TestPatterns`, `GenerateScenarioUseCase`, `ScenarioComposer`, `CampaignYamlWriter`, `SqliteExplorationRepository`.
`app/panel/explorer`: `PanelExplorerAdapter`, `TestCompanyRoleSessions`, `CatalogSetupProfiles`, `AnswerBook`.
`scenarios`: `TriageRunUseCase`, `ScenarioCatalog`.

## Verification

- `explorer`: `ExploreSiteUseCaseTest`, crawl/heuristics/classifier tests, `ExplorerFakeTargetIntegrationTest`.
- `app`: `TestCompanyRoleSessionsTest` (refusals, sessions, profile from the catalog, teardown on cancel),
  `PanelExplorerTest`, `AnswerBookTest`, `ExplorationTrackerTest`.
- `scenarios`: `TriageRunUseCaseTest`.

## Open items

- No credentials of its own, no self sign-up, no fallback yet (Faza 10). Drafts write no flows into `target_profile`.
