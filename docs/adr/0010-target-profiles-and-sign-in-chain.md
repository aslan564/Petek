# ADR-0010: Target profiles, a sign-in strategy chain and evidence tiers

**Status:** Accepted
**Date:** 2026-09-25
**Deciders:** Aslan (owner)

## Context
Today one process has one target (`PETEK_TARGET`); token, mailbox and mail domain are global; the panel refuses runs
on any other site. The explorer never signs in by itself: logged-in crawling exists only when a test company can be
created through the `/test` API, and there is no place for the owner to provide accounts and no fallback. Mail is
Mailpit only (`TestApiMailbox` exists but is not wired; no IMAP). The owner has 2–3 different sites and wants the
explorer to get in by the best available means, and to test sites that offer no test API at all.

## Decision
- **Target profile** `targets/<name>.yaml`: URL, separate API base URL, production hosts, mail source and domain,
  test-API token reference, the sign-in chain, owner accounts (secret references only), and a pointer to the
  existing `target_profile` (selectors, flows). `PETEK_TARGET` names the default profile. Run and explore pick a
  profile; the "only PETEK_TARGET" block in the panel is lifted.
- **Sign-in chain** (`SignInStrategy` port, ordered): `test_company` → `own_accounts` → `self_register` → `anonymous`.
  Each attempt and each fallback is an event in the evidence and a line in the report. The explorer's role sessions
  and the testers' `register_and_login` share the chain. Sessions (`storage_state`) are kept per (target, identity)
  and reused; a stale session falls back to the `login` flow.
- **Mail sources**: `mailpit`, `test-api`, `imap` (catch-all or plus addressing), `manual` (the panel asks the owner
  for the code; meant for the explorer's few sessions, only a warning for the swarm).
- **Capability probe** before exploration records what the target supports (test API, mail source, realtime
  transport, CAPTCHA/rate-limit signs) as `TargetCapabilities`.
- **Evidence tier** on every finding: `ORACLE_CONFIRMED`, `UI_NETWORK`, `LLM_JUDGED`. A target without an oracle is a
  supported mode, not a degraded one: oracle assertions read "N/A (no oracle)", the tier tells the reader what the
  verdict rests on.
- Secrets in profiles are `${ENV}` references resolved from `.env`; the panel writes secrets to `.env`, never to the
  database (rule 10).

## Options Considered
- **A. One `.env` per site, run one at a time.** Works today, but no chain, no owner accounts, no evidence tiers.
- **B. Profiles inside each campaign YAML.** Mixes what is tested with where; secrets would land in scenario files.
- **C (chosen). Separate target profiles + strategy chain + tiers.** Sites become data; the chain makes sign-in
  policy explicit and auditable.

## Consequences
- Easier: several sites in one panel; the explorer gets in on real sites; reports say how much each verdict is worth.
- Harder: an IMAP dependency (Jakarta Mail/Angus proposed, rule 11); the manual-code path adds a human step the
  orchestrator must wait on with a timeout; profile validation becomes a new source of configuration errors.
- Rule 8 stays: oracle and teardown only on `is_test=true` companies of the configured target; production hosts
  still need `PETEK_ALLOW_PRODUCTION=true` per profile.
