# ADR-0012: Link-only swarm — verified ownership, a gate learnt once, isolated cards

**Status:** Accepted (amended 2026-09-27: visitor runs on unproved sites)
**Date:** 2026-09-26
**Deciders:** Aslan (owner)

## Context
Pətək runs YAML campaigns against a target whose contract (test API, Mailpit) has been prepared. The owner wants a second
face: give a link, optionally with an instruction document, and Pətək learns the site, gets its testers in, hands each a
mission of its own and reports with evidence — big crashes and small bugs alike, on shops, news sites, showcase sites and
sign-in systems. The design was worked out with the owner on 2026-09-26 and is recorded in `docs/LINK_ONLY_SWARM.md`
(section 0 holds the owner's decisions).

Forces: a tool that signs up accounts and writes to whatever link it is given can be aimed at someone else's site; an
account used by two testers at once corrupts both sessions and the evidence; testers that know each other stop behaving
like independent users; thirty testers asking the AI how to sign up cost thirty times as much and fail in thirty ways.

## Decision
- **Targets are pre or stage environments.** Production hosts stay behind rule 8. A **full test** — anything that writes:
  runs, sign-up, the explorer's logged-in and touching phases — runs only on a site whose **ownership is verified**
  (teardown is not held back: it only removes a run's own test data through the token-protected test API): the file `/.well-known/petek-verification.txt` or the DNS TXT record `_petek-verification.<host>` holds
  `petek-verification=<token>`, the token being an HMAC of the host under the identity secret. Loopback, `localhost`,
  private-network and link-local addresses are exempt. A verification is remembered and re-checked after 30 days.
  Without it Pətək only reads.
- **Visitor runs** (the owner's decision of 2026-09-27). Reading is allowed without the proof, so a run that only
  reads may start on an unproved site too: all testers visitors (`tenant: none`, gate `guest`), no `do` step (an AI
  agent may click and type), `run` steps only of the read-only functions (`register_and_login`, which opens the home
  page for a visitor, `site_health` and `page_checks`), and no check that writes or needs the test API (`oracle`,
  `only_one_succeeds`, `http_status` other than GET). `VisitorRun` (campaign domain) decides; `petek run`, the panel and
  MCP apply it and name what keeps any other campaign from being one. The explorer's draft for a site without sign-in
  is such a campaign. The owner chooses how many testers take part and all of them work at once (first at most 3;
  amended the same day by the owner: the tool never limits testers by site, only the machine's capacity and the AI plan
  bound them). A visitor run may go to any site the owner gives, like the explorer; a run that writes still needs the
  proof, and `PETEK_TARGET` or a target profile for its site.
- **The explorer** classifies the site (shop, news, showcase, sign-in system, other), maps the gate from outside
  (pass 0), then goes inside with an account of its own (pass 1): the instruction document's explorer account, else one
  it registers. It never shares that account with a tester and keeps exploring until the run ends; what it finds
  extends the next run's scenario, not the current one.
- **Gates.** The scenario assigns every tester a gate: `register`, `login` (the instruction document's test accounts —
  real users' accounts are forbidden by the usage rules; passwords travel as `Secret`) or `guest`. The gate is learnt
  once with AI and replayed by code; a gate barrier holds the missions until everyone is in.
- **Isolation.** One tester, one browser environment; an account is in one tester at a time. On the operator's
  permission, testers that finished may swap accounts to change the point of view. Testers never see each other: the
  colleague roster leaves the prompt, and a value that belongs to another tester reaches a card as a placeholder the
  harness fills.
- **Mail.** The owner's mailbox with plus addressing over IMAP first, Mailpit for developers; a hosted inbox later, as a
  paid module that only receives and forgets after a day.
- **Report.** Every finding on one of three shelves (site bug, tool gap, scenario error); a simple customer layer over a
  detailed layer; JUnit XML and SARIF for CI.
- **Distinct IP per tester** is an opt-in on ownership-verified sites only, through the owner's own proxies. Without it
  an IP-limit answer is recognised and shelved as a tool gap, with the reason.

## Consequences
+ A link is enough for a meaningful test; the swarm stays cheap and repeatable (Rejim B learns, Rejim A repeats).
+ The ownership proof keeps the tool from being aimed at another's site; the opt-ins that could look like evasion
  (sign-up at scale, distinct IPs) exist only behind it.
- New feature module `ownership`; an IMAP integration that needs a library decision (rule 11); orchestrator changes
  (gates, barrier, account leases); the agent prompt loses the colleague roster.
- Existing runs against a real stage now need its owner to place the proof once (loopback targets are unaffected).

Extends ADR-0007 (layered target safety) and ADR-0010 (sign-in chain); supersedes nothing. Phases: Faza 15–22.
