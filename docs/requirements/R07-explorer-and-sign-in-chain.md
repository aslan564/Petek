# R07 — The explorer learns an unknown site, drafts scenarios and gets in by the best available means

**Status:** Implemented for the explorer (exploration, drafts, triage, sign-in chain); the testers' own gate is Faza 18 · **Plan:** Faza 6, 7, 10 · **ADRs:** 0009, 0010

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
  answers one structured question per page (`PageAnalyst`). After an accepted submit the trial touch asks the test
  API, as the role that created the object (the sessions' e-mails), whether it serves the object's resource
  (`TestApiProbe`, Faza 25.2); drafts write oracle checks only for resources proven so.
- `SiteModel` with `Provenance` (observed/inferred), versioned per target, event-logged; `CompareExplorationsUseCase`
  diffs versions. `TestPatterns` derive ideas; `GenerateScenarioUseCase` drafts a campaign the validator accepts,
  named once per site (`explorer-<host>`: every exploration's draft is the next version, so versions compare) and with
  the owner's tester count. Its site-wide checks (`site_health`, `page_checks`) are done by every tester at once, each
  in its own browser with a job of its own (`share: work`, `devices: phone,tablet,desktop`: every page on each device
  is a job, dealt out, and dealt round again as a second look when there are more testers than jobs; a page's links
  are asked about once); where people sign in, the pages a visitor sees are checked before anyone signs in and each
  role's own pages after the scenario (owner's decision, 2026-09-27).
- Logged-in sessions are opened once the visitor's walk is done (`RoleWalkSource`, Faza 25.1), with the site as that
  walk saw it, and only when a phase needs them. `TestCompanyRoleSessions` (app) then creates a test company through the
  test API with the site's own target profile from the scenario catalog (`CatalogSetupProfiles`, Faza 8), but only
  where the site has companies: the profile's `tenant: company`, or, without the owner's word, a form to join by
  invitation or company code the visitor's walk saw (`GateMaps.joinPages`); `tenant: none` never. A test API alone
  is no such sign.
- The site's kind and its gate are what a visitor sees (`SiteKinds.visitorPages`): the pages the logged-in walk adds
  never make a portal a shop, and an admin's "add user" form inside is never taken for the sign-up (`SiteKindsTest`).
- Findings are recorded by code, never judged by the AI: broken links, HTTP errors, slow pages, accessibility gaps,
  leaked error text, and what the browser itself saw go wrong on each page since it started loading (script errors
  and uncaught exceptions, failed requests to the site: `CONSOLE_ERROR`, `FAILED_REQUEST`), plus the page measured at
  a phone's width (375×812, `MOBILE_OVERFLOW`). All of it is reading, so an anonymous exploration of a site whose
  ownership is not proved already catches these small bugs (first seen on a real site, 2026-09-26: a
  home page 12 px wider than a phone and 4.4 s to load). The expired-session test idea is proposed only where people
  sign in.
- Unknowns are questions; answers live in `AnswerBook` and ground later explorations and drafts.
- `PageCapture` waits for a page that loaded empty to render (single-page applications draw after `load`; polled for
  `pageSettleTimeout`, 4 s by default) before it snapshots, so the analyst judges the page, not the empty shell. The
  analyst writes in the page's language and, without a clue, in Azerbaijani. Both from the first exploration of a real
  single-page application (2026-09-26).
- Triage (`features/scenarios`) classifies surprises and proposes scenario v2 as a diff. A failed check that code made
  on what the browser saw (a `SITE_CHECK` finding) is decided by code as a defect of the site (`CodeTriage`), with no
  model; only the rest needs the AI, and without one the panel says so and how to connect it.

## Architecture (Faza 10, ADR-0010)

- `SignInChain` (app): the methods of the site's target profile (`sign_in`, default `test_company` → `own_accounts` →
  `self_register` → `anonymous`) are tried in order until one yields logged-in sessions; every attempt and fallback
  is a line of the exploration's activity. `test_company` is used only where the site has companies (above);
  `self_register` opens a company only when the profile says `tenant: company`; otherwise the explorer signs up as a
  plain user (Faza 25.1).
- Drafts choose their tenant by `GateMaps.tenantFor` (Faza 25.1): the profile's `tenant`, else companies only when
  the explorer saw the site's own way into one (`GateMaps.companyWay`: a form taking an invitation or company code,
  and an operation of a signed-in role handing them out) and the test API can seed the test company; a test API
  alone never makes a company draft. When the explorer saw a code but not who gives it, it asks, and the draft takes
  the gate as it is.
- `OwnAccountRoleSessions`: the owner's accounts from the panel ("Hesablar", password to `.env` as
  `PETEK_ACC_<SITE>_<ROLE>`, the account with its `${VAR}` reference to `targets/<site>.yaml`; the database never
  sees it) or from the target profile. A given `storage_state` file is used as is; a session saved by an earlier
  exploration (`<evidence>/sessions/<site>/<role>.json`, `rw-------`) is reused while it is still signed in. A saved
  state carries sessionStorage too (`origins[].sessionStorage`, put back into the tab once when it first opens a page
  of that origin), so a single-page application that keeps its login there stays signed in; a token the site keeps
  only in memory cannot be saved by any browser state.
- Signing in plays the profile's own `login` flow (`ExplorerLoginFlow`), the one the testers run, so a login form that
  asks for more than an e-mail and a password (a company code, a workspace, a consent box) works: an account's
  `fields:` (non-secret values, e.g. `{company_code: ACME-42}`) fill its `{self.<name>}`, `{shared.<name>}` and
  `{vars.<name>}` templates. Page steps are played (`goto`, `fill`, `select`, `check`, `click`, `click_if_visible`,
  `wait_for`, `expect_url`, `if_visible`, `assert_identity`); a flow that needs a tester's run (an e-mail or phone
  code, `read`, a journey) or a value the account lacks falls back to the form's main fields plus every field the
  profile names a `login.<name>` selector for. When the form stays, the owner reads why: how many required fields the
  browser holds invalid and the first one's name, else the site's own `login.error` text — never a bare "could not
  sign in".
- `SelfRegisterRoleSessions`: the explorer registers its own account through the site's registration flow, only with
  the owner's permission for writes and only on the configured site.
- Mail sources `test-api`, `mailpit`, `imap` and `manual` (the panel asks the owner for a code).
- Capability probe (`petek probe`); evidence tiers on findings.

## Modules and key types

`explorer`: `ExploreSiteUseCase`, `CrawlPass`, `TrialToucher`, `PageAnalyst`, `SiteModel`, `SiteModelAccumulator`,
`TestPatterns`, `GenerateScenarioUseCase`, `ScenarioComposer`, `CampaignYamlWriter`, `SqliteExplorationRepository`.
`app/panel/explorer`: `PanelExplorerAdapter`, `SignInChain`, `TestCompanyRoleSessions`, `OwnAccountRoleSessions`,
`SelfRegisterRoleSessions`, `ExplorerLoginFlow`, `CatalogSetupProfiles`, `AnswerBook`. `app/panel`: `OwnerAccounts`.
`campaign`: `TargetSpec`, `OwnAccount` (`fields`), `YamlTargetSpecSource`.
`scenarios`: `TriageRunUseCase`, `ScenarioCatalog`.

## Verification

- `explorer`: `ExploreSiteUseCaseTest` (including script errors, failed requests and phone-width overflow as findings),
  `TestPatternLibraryTest`, crawl/heuristics/classifier tests, `ExplorerFakeTargetIntegrationTest`.
- `app`: `TestCompanyRoleSessionsTest` (refusals, sessions, profile from the catalog, teardown on cancel),
  `SignInChainTest` (order and fallbacks, saved sessions, the profile's login flow with account fields, the reason a
  login form stays), `ExplorerLoginFlowTest`, `OwnerAccountsTest` (a re-given account keeps its `fields`),
  `PanelExplorerTest`, `AnswerBookTest`, `ExplorationTrackerTest`.
- `campaign`: `YamlTargetSpecSourceTest` (accounts, `fields` and their names).
- `scenarios`: `TriageRunUseCaseTest`.

## Open items

- The panel's "Hesablar" card takes an e-mail and a password; an account's `fields` are written in
  `targets/<site>.yaml` (the panel keeps them when the account is given again).
- The testers still pass the campaign's own gate; a gate per tester is Faza 18. Drafts write no flows into
  `target_profile`.
