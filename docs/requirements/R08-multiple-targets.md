# R08 — Two or three different sites from one Pətək, each with its own settings

**Status:** Implemented (Faza 10) · **ADRs:** 0010

## Requirement

The owner has several sites. One Pətək installation must know them all: each with its own URL, API
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
- `PETEK_TARGET` names the default profile (a name or a URL); `PETEK_TARGETS_DIR` the folder. `RunTargets` leases a
  container per profile (`TargetProfileConfig.forTarget`: the profile's URL, test API, token, production hosts and
  mail over the panel's configuration, sharing the panel's database); the oracle, mailbox and policy follow the
  profile. The panel runs any site that has a profile; MCP `list_targets` lists them. The panel's **Saytlar** card
  (Faza 23, `PanelSitesAdapter`) lists them with their settings and adds a site as a new profile, its token in `.env`,
  taken without a restart (`AppContainer.refreshTargets`).
- A scenario runs on its own site (its `campaign.target`), or on the "Hədəf sayt" when the owner gives one; never on
  the site the panel happened to be opened for (first seen 2026-09-27: a draft for one site ran against another). An
  explorer draft only runs on the site it was written for. A site without a profile gets the panel's settings without
  anything of the panel's own site (no test API, token or oracle); there a visitor run (only reads) may start, like the
  explorer, while a run that writes still needs `PETEK_TARGET` or a profile.
- Secrets in profiles are `${ENV}` references resolved from `.env`; the panel writes secrets to `.env`, never to the
  database (rule 10). Accounts carry non-secret login `fields` (R07). `PETEK_TEST_TOKEN` belongs to the panel's own
  site: only a profile of that site inherits it; a profile of another site that names no token of its own has none
  (its oracle is "N/A"), so the token never reaches another site's test API (found 2026-09-29).
- Capability probe per target records what it supports; evidence tiers say what each verdict rests on.

## Modules touched

`campaign` (domain model + YAML), `app/config`, `app/di` (`RunTargets`, container per target), `app/panel`
(target list, run/explore selection), `oracle`, `mail`, `dashboard` (target selector view).

## Verification

- `campaign`: `YamlTargetSpecSourceTest` (parsing, validation with lines, secret references, accounts and fields,
  the example target profile).
- `app`: `ConfigLoaderTest` (`PETEK_TARGET` naming a profile, a broken profile reported with its file and line, a
  profile of another site never getting the panel's token),
  `PanelRunsTest` (a site with a profile runs with its settings; a scenario runs on its own site; a scenario that
  writes is refused on another site without a profile), `OwnerAccountsTest`, `PanelSitesTest` (the list, a site added
  and taken at once, refusals, both files put back); `PanelEndToEndTest` adds and chooses a site in Chromium.
- Open: an e2e run against a second fake site in the same panel (Faza 13).

## Open items

- Whether per-target evidence directories or one database with `target` columns (default: one database, `target`
  recorded on runs and explorations, as today).
