# R04 — Deterministic identities, invitation and company-code sign-up, e-mail and phone OTP

**Status:** Implemented (mail from Mailpit, the test API, IMAP or the owner by hand) · **Plan:** Faza 1, 4, 8, 10 · **ADRs:** 0004, 0010

## Requirement

Each run creates its testers itself: unique names, e-mails on the test domain, unguessable but reproducible
passwords, phones, roles, departments. Testers register the way real users do — the owner signs up and creates the
company, managers join by invitation, employees by invitation or with the company code — and complete e-mail and
phone verification without a human. The owner decided: both registration modes, per tester.

## Why

Sign-up is the first real-user flow and the gate to everything else; it must be deterministic (same plan twice gives
the same identities) and independent of mailboxes a human reads.

## Architecture

- **Registry.** `DefaultIdentityRegistryGenerator` (`features/identity`) derives identities from the campaign seed and
  a run tag; passwords are HMAC(`PETEK_IDENTITY_SECRET`) so they are reproducible and never stored in clear; only the
  orchestrator creates identities (rule 7). `RegistrationQuota` splits non-admins into invited and company-code
  joiners; managers are always invited (the join form has no role field).
- **Flows as data.** Sign-up, join and login are `TargetProfile.flows` (contract defaults, overridden per site in
  YAML); `RunFunction`s execute them: `register_owner`, `seed_company` (departments and invitations through the test
  API), `register_and_login` (by the identity's `RegistrationMode`).
- **Mail.** The `Mailbox` port (`features/mail`) with `MailpitMailbox` and `TestApiMailbox`, chosen by
  `PETEK_MAIL_SOURCE`; `AwaitVerificationUseCase` extracts codes and links (also by a site's own link pattern); only
  mail received after the run start is read and it is marked read after use.
- **Phone OTP.** From the target's test API (`GET /test/otp/{phone}`), never from a real SMS provider in the MVP.
- **Secrets.** Passwords travel as `Secret`; the model types `{self.password}` (rule 10).

## Modules and key types

`identity`: `Identity`, `IdentityPlan`, `IdentityRegistryGenerator`, `HmacPasswordDeriver`, `SqliteIdentityRepository`.
`mail`: `Mailbox`, `MailpitMailbox`, `TestApiMailbox`, `DefaultVerificationExtractor`, `AwaitVerificationUseCase`.
`agent`: `RegisterOwnerRunFunction`, `SeedCompanyRunFunction`, `RegisterAndLoginRunFunction`, `FlowRunner`.
`app/config`: `MailSource`, `PetekConfig.testApiBase`.

## Verification

- `identity`: generator determinism, uniqueness for large N, quota tests.
- `mail`: `MailpitMailboxTest`, `TestApiMailboxTest` (Ktor fake servers), `ImapMailboxTest`, `ManualCodesTest`,
  extractor tests.
- `agent`: run-function tests over the contract flows and portal-shaped flows with `FakeMailbox`.
- `app`: `ConfigLoaderTest` (mail source, test API URL), `AppContainerTest`, `DoctorCommandTest` ("Test inbox").
- `e2e`: full sign-up of many testers against the fake target.

## Done in Faza 10 (ADR-0010)

- `ImapMailbox` for sites whose SMTP the owner does not control (catch-all or plus addressing).
- `ManualCodeDesk` / `ManualCodeMailbox` (`PETEK_MAIL_SOURCE=manual`, the default of `.env.example`): the panel asks
  the owner for a code; meant for the explorer's few sessions, not a swarm.
- Owner-provided accounts as a sign-in strategy and reuse of saved `storage_state` (R07).

## Open items

- A gate per tester (register, log in with a given account, or guest) is Faza 18 (R16).
