# R08 — Two or three different sites from one Pətək, each with its own settings

**Status:** Planned (Faza 10) · **ADRs:** 0010

## Requirement

The owner has several sites, not only KadroHR. One Pətək installation must know them all: each with its own URL, API
base, test token, mail source, production hosts, sign-in chain and accounts, selectable in the panel and on the CLI,
without editing `.env` per site.

## Why

Today one process has one target (`PETEK_TARGET`) and every other setting is global; the panel refuses runs on any
other site because it would receive this site's token and flows. Multi-site is the first step towards a universal
tool (R11) and a sellable product (R15).

## Architecture (Faza 10, ADR-0010)

- **Target profile** `targets/<name>.yaml` as a domain model (`TargetSpec` in `features/campaign`, YAML DTO in
  infrastructure): `url`, `api_url`, `production_hosts`, `mail {source, domain}`, `test_api {token: ${ENV}}`,
  `sign_in` chain, `accounts` (secret references), pointer to the existing `target_profile` (selectors, flows).
- `PETEK_TARGET` names the default profile; `PETEK_TARGETS_DIR` the folder. `RunTargets` builds a container per
  profile (today it copies the config with another URL); the "only `PETEK_TARGET`" block in `PanelRunsAdapter` is
  lifted; the oracle, mailbox and policy follow the profile.
- Secrets in profiles are `${ENV}` references resolved from `.env`; the panel writes secrets to `.env`, never to the
  database (rule 10).
- Capability probe per target records what it supports; evidence tiers say what each verdict rests on.

## Modules touched

`campaign` (domain model + YAML), `app/config`, `app/di` (`RunTargets`, container per target), `app/panel`
(target list, run/explore selection), `oracle`, `mail`, `dashboard` (target selector view).

## Verification (planned)

- Profile parsing and validation tests; two fake targets in one panel (fake KadroHR + a plain login-only fake site);
  a run on the second site never carries the first site's token (`PanelRunsTest`).

## Open items

- Whether per-target evidence directories or one database with `target` columns (default: one database, `target`
  recorded on runs and explorations, as today).
