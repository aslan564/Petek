# R06 — Target safety: never harm production, write only to test data, oracle only on `is_test`

**Status:** Implemented · **Plan:** Faza 0, 5, 15 · **ADRs:** 0007, 0012

## Requirement

Pətək must never test a system it was not pointed at, never write to a production tenant, and never delete anything
that is not test data. Every destructive capability (seeding, teardown, trial touch) is guarded twice: by
configuration and by the target's own `is_test` flag; on a site without companies, where no such flag exists, the
explorer's trial touch writes only from the account it signed up with in the same exploration, on a site whose
ownership is proved or local, and marks every text it types (the owner's decision of 2026-09-30).

## Why

The tool creates accounts, companies and content at scale. One wrong URL must fail loudly instead of polluting or
destroying a customer's data.

## Architecture

- **Target policy (rule 8).** `TargetPolicy` (`core/domain`) refuses hosts in `PETEK_PRODUCTION_HOSTS` unless
  `PETEK_ALLOW_PRODUCTION=true`; `PETEK_TARGET` replaces `campaign.target` so a scenario file cannot redirect a run;
  the CLI (`TargetGuard`), `doctor` and the panel (`PanelTargets`) apply the same policy to every URL.
- **Testers stay on the site (Faza 24.9).** The policy holds during a run too, not only when the target is chosen:
  a tester may be only on the target's host and the hosts the owner allowed (`allowed_hosts` of the target profile,
  `PETEK_ALLOWED_HOSTS`: a sign-in service, the host of an e-mail link). An absolute URL elsewhere is refused before
  the browser moves, a page a click or redirect took elsewhere is brought back before the model sees it (`off_site`
  when it keeps leaving), and each browser context refuses to open a production host or write to one
  (`SessionOptions.blockedHosts`), the target itself excepted when it was allowed.
- **Test API guard.** `HttpTargetOracle` sends `X-Test-Token` only to the configured test API base and never follows
  redirects; company lookups and teardown refuse companies without `is_test=true`; the explorer's trial touch requires
  the owner's `allowWrites` and a `TestTargetCheck` that confirms test data: the test company through the API
  (`OracleTestTargetCheck`) or, on a site without companies, the explorer's own new account on the configured, proved
  or local site (`ExplorerAccountTestCheck`); the owner's own accounts never write.
- **An API on its own host.** A full `api_prefix` (`https://api.example.com/v1`, 2026-09-30) is the only other origin a
  run may call, and only from `http_status` checks: before the run starts it passes the production-host policy and
  proves its own ownership like the target (`petek run` exits 2 with the proof to publish; the panel refuses under
  "Hədəf sayt"); oracle paths there are refused, the test API stays on the target.
- **Teardown.** Every run ends with teardown (also when aborted or interrupted); `petek teardown --run` repeats it;
  `--keep-data` is explicit and for debugging.
- **Panel runs.** A run goes to the configured site or to a site with its own target profile (R08), which brings its
  own test API, token, production hosts and mail; another site never receives this site's token or flows.
- **Only the site that was given (rule 12, the owner's decision of 2026-09-26).** No invented screens, pages or
  results, no stand-in site. `TargetReachability` (`app/diagnostics`; `HttpTargetReachability` over `HttpProbe`,
  `AppContainer.reachability`, `TargetReachability.ALWAYS` in tests with a fake browser) looks at the target before
  `petek run`, a panel run and an exploration open a browser: no answer or a 5xx is `TargetUnreachableException`
  (exit 2) on the command line or a `PanelRequestException` under the target field in the panel and MCP, and nothing
  is tested. A target that answers only with a CDN's error or challenge page (`CdnErrorPage`: Cloudflare's
  `error code: 1000`-style pages and `cf-mitigated` challenges, CloudFront, Akamai, Sucuri, Imperva) counts as not
  answering, with the CDN's reason. Without a configuration `petek panel` serves one question in the browser
  (`SetupServer` over `PanelSetup`: loopback only, the page's token on the POST): the site to test must answer, is then
  written to `.env` from the template (never over an existing file, `rw-------`) and the panel opens for it; nothing
  else starts before the answer. `petek mcp` still answers the
  handshake but serves `UnavailablePanelBackend(NO_TARGET)`, so every tool tells the host AI to ask the owner which
  site to test and wait. The former fallback to a local fake target when `.env` was missing (`--demo`, `DemoTarget`)
  is gone; the fake target is a developer stand-in reached only through an explicit `--env-file .env.fake-target`.
- **TLS.** The browser verifies certificates like a user's would; a target with a broken certificate is a finding.
  `PETEK_BROWSER_IGNORE_TLS_ERRORS=true` accepts untrusted certificates for a self-signed staging or a network whose
  proxy re-signs traffic; it is off by default and `petek doctor` names it in the configuration row when it is on.

## Modules and key types

`core/domain`: `TargetPolicy`, `TargetVerdict`. `oracle`: `HttpTargetOracle`, `OracleTeardownUseCase`. `app`:
`TargetGuard`, `PanelTargets`, `OracleTestTargetCheck`, `OracleTestApiProbe`. `explorer`: `TestTargetCheck`.

## Verification

- `app`: `TargetGuardTest`, `ConfigLoaderTest` (policy), `PanelTargetsTest`, `OracleTestTargetCheckTest`,
  `OracleTestApiProbeTest`, `TeardownCommandTest`; rule 12: `RunCommandTest` (a site that does not answer: exit 2,
  no browser, no run record), `PanelRunsTest` and `PanelExplorerTest` (refusal under the target field, nothing
  starts), `McpCommandTest` (no configuration: the host AI is told to ask the owner), `PetekCliTest` (no configuration:
  the browser asks, nothing starts before the answer, then `.env` and the panel), `PanelSetupTest`, `SetupServerTest`,
  `CdnErrorPageTest`.
- `oracle`: `HttpTargetOracleTest` (token handling, `is_test` refusal, no redirects).
- `explorer`: trial touch refused without a confirmed test target; `ExplorerAccountTestCheckTest` (another site, an
  unproved site and a failed look are refused), `TestCompanyRoleSessionsTest` (only an account the explorer signed up
  with itself is written from).
- ownership (ADR-0012): `features/ownership` domain tests (proof line, exemptions, ledger); `RunCommandTest` (public
  stage unproved → exit 2 with the proof to publish and why the campaign is not a visitor run, a visitor run → runs,
  proved → runs, local → exempt); `VisitorRunTest`; `PanelRunsTest` (a visitor run starts on an unproved site); `PanelRunsTest` and
  `PanelExplorerTest` on a public stage host (run refused with file and DNS instructions; explorer reads anonymously,
  role and trial phases skipped); `PanelTargetsTest` (`owned`, `proofHowTo` for a host and an IP); `VerifyCommandTest`.

## Open items

- Per-target production hosts and tokens in target profiles (R08, Faza 10) — the policy stays, the scope widens.
