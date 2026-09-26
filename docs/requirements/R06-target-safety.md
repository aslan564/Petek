# R06 — Target safety: never harm production, write only to test data, oracle only on `is_test`

**Status:** Implemented · **Plan:** Faza 0, 5 · **ADRs:** 0007

## Requirement

Pətək must never test a system it was not pointed at, never write to a production tenant, and never delete anything
that is not test data. Every destructive capability (seeding, teardown, trial touch) is guarded twice: by
configuration and by the target's own `is_test` flag.

## Why

The tool creates accounts, companies and content at scale. One wrong URL must fail loudly instead of polluting or
destroying a customer's data.

## Architecture

- **Target policy (rule 8).** `TargetPolicy` (`core/domain`) refuses hosts in `PETEK_PRODUCTION_HOSTS` unless
  `PETEK_ALLOW_PRODUCTION=true`; `PETEK_TARGET` replaces `campaign.target` so a scenario file cannot redirect a run;
  the CLI (`TargetGuard`), `doctor` and the panel (`PanelTargets`) apply the same policy to every URL.
- **Test API guard.** `HttpTargetOracle` sends `X-Test-Token` only to the configured test API base and never follows
  redirects; company lookups and teardown refuse companies without `is_test=true`; the explorer's trial touch requires
  the owner's `allowWrites` and a `TestTargetCheck` that confirms test data through the API.
- **Teardown.** Every run ends with teardown (also when aborted or interrupted); `petek teardown --run` repeats it;
  `--keep-data` is explicit and for debugging.
- **Panel runs.** Until per-target profiles exist (R08), a run goes only to the configured site, so another site never
  receives this site's token or flows.
- **Only the site that was given (rule 12, the owner's decision of 2026-09-26).** No invented screens, pages or
  results, no stand-in site. `TargetReachability` (`app/diagnostics`; `HttpTargetReachability` over `HttpProbe`,
  `AppContainer.reachability`, `TargetReachability.ALWAYS` in tests with a fake browser) looks at the target before
  `petek run`, a panel run and an exploration open a browser: no answer or a 5xx is `TargetUnreachableException`
  (exit 2) on the command line or a `PanelRequestException` under the target field in the panel and MCP, and nothing
  is tested. Without a configuration `petek panel` asks for the site and exits with 2; `petek mcp` still answers the
  handshake but serves `UnavailablePanelBackend(NO_TARGET)`, so every tool tells the host AI to ask the owner which
  site to test and wait. The former fallback to a local fake KadroHR when `.env` was missing (`--demo`, `DemoTarget`)
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
  starts), `McpCommandTest` and `PetekCliTest` (no configuration: the owner is asked, nothing starts).
- `oracle`: `HttpTargetOracleTest` (token handling, `is_test` refusal, no redirects).
- `explorer`: trial touch refused without a confirmed test target.

## Open items

- Per-target production hosts and tokens in target profiles (R08, Faza 10) — the policy stays, the scope widens.
