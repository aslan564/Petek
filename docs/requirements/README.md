# Requirements and their architecture

One document per requirement: what the owner asked for, why, how the architecture satisfies it, which modules and
types carry it, how it is verified, which decisions (ADRs) and plan phases it rests on, and what is still open. A pull
request that changes behaviour updates the requirement it touches (see `.github/pull_request_template.md`).

Status: **Implemented** (in `develop`, covered by tests), **Partial** (core exists, gaps listed), **Planned** (phase named).

| Id | Requirement | Status | Modules | ADRs | Plan |
|---|---|---|---|---|---|
| [R01](R01-concurrent-multi-agent-testing.md) | Many AI testers at once in isolated browser sessions; isolation audited and proven at 5 000 (harness) / 60 (real Chromium) | Implemented | orchestration, browser, identity, agent, campaign | 0001, 0002, 0006 | Faza 2–3, 8 |
| [R02](R02-deterministic-harness.md) | Code decides: clock, assertions, action whitelist, `run` vs `do` | Implemented | core, agent, verification, campaign | 0003, 0004, 0007 | Faza 2, 4 |
| [R03](R03-evidence-based-reporting.md) | Every verdict backed by evidence; ids everywhere; three-source judge; stability | Implemented | evidence, reporting | 0005, 0007 | Faza 5 |
| [R04](R04-identities-registration-and-otp.md) | Deterministic identities; invitation and company-code sign-up; e-mail and phone OTP | Implemented | identity, mail, oracle, agent | 0004 | Faza 1, 4, 8 |
| [R05](R05-realtime-measurement.md) | Real-time delivery measured in the DOM, transport detected, never assumed | Implemented | orchestration, browser, verification | 0006 | Faza 4 |
| [R06](R06-target-safety.md) | Never harm production; write only to test data; oracle only on `is_test` | Implemented | core, oracle, app | 0007 | Faza 0, 5 |
| [R07](R07-explorer-and-sign-in-chain.md) | Learn an unknown site, draft scenarios, get in by the best available means | Partial | explorer, scenarios, app | 0009, 0010 | Faza 6, 10 |
| [R08](R08-multiple-targets.md) | Two or three different sites from one Pətək, each with its own settings | Planned | campaign, app | 0010 | Faza 10 |
| [R09](R09-ai-provider-agnostic.md) | Works with whatever AI the host project uses; never tied to one vendor | Partial | llm, app | 0003, 0008 | Faza 9 |
| [R10](R10-tool-surface-and-skill-pack.md) | Any host AI drives Pətək (MCP, `--json`, skill pack); root cause in the host's repository | Planned | dashboard, app | 0009 | Faza 11–12 |
| [R11](R11-universal-target-model.md) | Tenant-optional core, free-form roles, blind test patterns for unknown sites | Planned | core, campaign, explorer | 0010 | Faza 13 |
| [R12](R12-security.md) | Secrets never leak; AI contained; panel local; supply chain pinned | Implemented | core, llm, browser, dashboard | 0004, 0007 | all |
| [R13](R13-code-quality-and-architecture-protection.md) | Architecture and quality enforced by the build, not by discipline | Implemented | build-logic, e2e | 0001 | Faza 8 |
| [R14](R14-licensing-and-intellectual-property.md) | The work is protected: BSL 1.1, headers on every file, trademark, edition boundary | Implemented | build-logic, all | 0011 | Faza 8 |
| [R15](R15-distribution-and-monetization.md) | Installable in any project, runs beside it and in CI; paid editions possible | Planned | app, build-logic | 0009, 0011 | Faza 12, 14 |

Traceability is kept in three places: this table, the `## Verification` section of each document (test classes), and
the PR template. The Konsist test in `e2e/` is the executable form of R13 and of the layer rules that R01–R12 rely on.
