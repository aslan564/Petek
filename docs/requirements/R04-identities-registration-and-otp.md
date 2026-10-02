# R04 — Deterministic identities, invitation and company-code sign-up, e-mail and phone OTP

**Status:** Implemented (mail from Mailpit, the test API, IMAP or the owner by hand) · **Plan:** Faza 1, 4, 8, 10 · **ADRs:** 0004, 0010

## Requirement

Each run creates its testers itself: unique names, e-mails on the test domain, unguessable but reproducible
passwords, phones, roles, departments. Testers register the way real users do — the owner signs up and creates the
company, managers join by invitation, employees by invitation or with the company code — and complete e-mail and
phone verification without a human. The owner decided: both registration modes, per tester. That is the company
model of the contract; since Faza 25.1 no site is assumed to have it: the owner's `tenant: company`, or the explorer's
sight of the site's own join form and of the role that hands out invitations or codes (with a test API to seed the
test company), chooses it, and otherwise testers pass the site's gate as it is (sign-up, the owner's accounts, guest).

## Why

Sign-up is the first real-user flow and the gate to everything else; it must be deterministic (same plan twice gives
the same identities) and independent of mailboxes a human reads.

## Architecture

- **Registry.** `DefaultIdentityRegistryGenerator` (`features/identity`) derives identities from the campaign seed and
  a run tag; passwords are HMAC(`PETEK_IDENTITY_SECRET`) so they are reproducible; only the orchestrator creates
  identities (rule 7). `RegistrationQuota` splits non-admins into invited and company-code joiners; managers are
  always invited (the join form has no role field).
- **Storage.** `SqliteIdentityRepository` keeps each run's registry in the `identity` table. The e-mail of a tester
  Pətək generates is unique across all runs (partial unique index `identity_generated_email_unique`, every row whose
  `registration` is not `login`, case-insensitive), and no generated e-mail may be one another run stored at all. A
  `login` tester signs in with the owner's account (R16), whose e-mail is the same in every run, so it repeats across
  runs: a campaign on the owner's accounts runs again, with `--repeat` and from the panel, on one database
  (2026-10-02). A generated tester's password is stored (R01, open items); the owner's account's password never is:
  the `login` row holds an empty one, the run signs in with the password of its in-memory plan, and triage masks every
  password of the target profiles as they are when triage runs. A password the owner changed or removed since a run is
  therefore not masked when that run is triaged: what keeps the owner's password out of a run's evidence, and so out
  of its triage, is the run's own redaction as it records (`AgentRuntime.redact`, the browser session's masking of
  typed secrets); triage's masking is a last layer over it, and keeping the old password to feed that layer would put
  it back in the database in clear. Opening a database an earlier release wrote creates the partial index, clears
  the owner's passwords stored there, rebuilds the file (`VACUUM`, then the write-ahead log emptied) so that no copy
  left in its free space by earlier updates and deletions survives, and only then drops the old index on every e-mail
  (`identity_email_unique`), so a rebuild that failed is done again at the next start; every row is kept.
- **Flows as data.** Sign-up, join and login are `TargetProfile.flows` (contract defaults, overridden per site in
  YAML); `RunFunction`s execute them: `register_owner`, `seed_company` (departments and invitations through the test
  API), `register_and_login` (by the identity's `RegistrationMode`).
- **Mail.** The `Mailbox` port (`features/mail`) with `MailpitMailbox`, `TestApiMailbox` and `ImapMailbox`, chosen by
  `PETEK_MAIL_SOURCE`; `AwaitVerificationUseCase` extracts codes and links (also by a site's own link pattern); only
  mail received after the run start is read and it is marked read after use. Which messages count as received since a
  moment is the mailbox's to say (an IMAP server keeps arrivals to the second). Scale (2026-10-01): the IMAP inbox
  serves every waiting tester in shared rounds (one connection, marks and searches batched, bodies cached per UID), so
  100 testers waiting at once all get their codes. Time spent behind other testers counts toward a tester's wait, but a
  last look runs after the deadline (bounded, 30 s), so a code that arrived in time is never called `mail_timeout`; an
  inbox too slow to answer that last look ends as `mail_unavailable` (the surroundings), as does a failing inbox or
  unreadable mail. A code already taken survives a failed or cut-off mark.
- **Phone OTP.** From the target's test API (`GET /test/otp/{phone}`), never from a real SMS provider in the MVP.
- **Secrets.** Passwords travel as `Secret`; the model types `{self.password}` (rule 10).

## Modules and key types

`identity`: `Identity`, `IdentityPlan`, `IdentityRegistryGenerator`, `HmacPasswordDeriver`, `SqliteIdentityRepository`.
`mail`: `Mailbox`, `MailpitMailbox`, `TestApiMailbox`, `DefaultVerificationExtractor`, `AwaitVerificationUseCase`.
`agent`: `RegisterOwnerRunFunction`, `SeedCompanyRunFunction`, `RegisterAndLoginRunFunction`, `FlowRunner`.
`app/config`: `MailSource`, `PetekConfig.testApiBase`.

## Verification

- `identity`: generator determinism, uniqueness for large N, quota tests; `SqliteIdentityRepositoryTest` (the owner's
  account stored by many runs, a generated e-mail refused across runs, no owner's password in the table or the file,
  an older database moved over with its rows, and one an earlier release ran a campaign on left with no copy of the
  owner's password in its file); `SqliteDatabaseTest` (a vacuum leaves nothing overwritten or deleted in the files).
- `app`: `RunCommandTest` runs a campaign on the owner's account twice and with `--repeat 2` on one database;
  `PanelRunsTest` runs it again from the panel and triages it without showing the model the owner's password.
- `mail`: `MailpitMailboxTest`, `TestApiMailboxTest` (Ktor fake servers), `ImapMailboxTest`, `ManualCodesTest`,
  extractor tests; `ImapMailboxServerTest` against a real IMAP server (GreenMail, test only, 2026-09-30): each tester
  finds only the mail to its own `+` address (To, Cc or `Delivered-To`), reading leaves it unread, a wrong password
  never shows.
- `agent`: run-function tests over the contract flows and portal-shaped flows with `FakeMailbox`.
- `app`: `ConfigLoaderTest` (mail source, test API URL), `AppContainerTest`, `DoctorCommandTest` ("Test inbox").
- `e2e`: full sign-up of many testers against the fake target; `ImapCodesEndToEndTest` signs an owner, an invited
  manager and an employee up with codes and the invitation read from the owner's IMAP inbox (the site's mail relayed
  over SMTP to GreenMail, Mailpit absent).

## Done in Faza 10 (ADR-0010)

- `ImapMailbox` for sites whose SMTP the owner does not control (catch-all or plus addressing).
- `ManualCodeDesk` / `ManualCodeMailbox` (`PETEK_MAIL_SOURCE=manual`, the default of `.env.example`): the panel asks
  the owner for a code; meant for the explorer's few sessions, not a swarm.
- Owner-provided accounts as a sign-in strategy and reuse of saved `storage_state` (R07).

## Open items

- A gate per tester (register, log in with a given account, or guest) is Faza 18 (R16).
