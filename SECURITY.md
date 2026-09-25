# Security policy

Pətək drives real browsers against real systems with real credentials, and it hands page content to an AI. Its
security posture is therefore part of the product, not an afterthought. This document is the threat model, the
controls in place, the hardening still planned, and how to report a vulnerability.

## Reporting a vulnerability

Please do **not** open a public issue. E-mail **aslanovaslan165@gmail.com** with the affected version (commit),
steps to reproduce and impact. You will get an acknowledgement within 72 hours and a fix or mitigation plan within 14
days for confirmed issues. Credit is given in the release notes unless you prefer otherwise.

Supported: the `develop` branch and the latest tagged release. Older branches (`petek-mvp*`) are kept as history and
receive no fixes.

## Assets and trust boundaries

| Asset | Where it lives | Who may see it |
|---|---|---|
| Test token (`PETEK_TEST_TOKEN`), AI API keys, identity secret | `.env` (git-ignored), process environment | The harness only |
| Test identities' passwords | Derived at run time (HMAC of the identity secret) | The harness; typed into the browser by the harness |
| Page content, screenshots, DOM snapshots | Evidence directory, SQLite | The owner; the AI sees redacted snapshots |
| The target system | Staging with test mode; production refused by default | Agents through the browser; the harness through `/test/...` |
| The panel | Loopback HTTP on the owner's machine | The owner's browser |

Trust boundaries: the AI is **untrusted** (it may hallucinate or be prompt-injected by page content); the target is
**semi-trusted** (its pages are untrusted input, its test API is trusted only for `is_test` companies); the operator is
trusted.

## Controls in place

**Secrets (CLAUDE.md rule 10)**
- Every secret is a `Secret` value: `toString` prints nothing, configs print `set`/`unset`, URLs are printed with
  credentials masked.
- The AI never receives a password: agents type the placeholder `{self.password}`, the harness substitutes it in the
  browser. Text taken from the page (dialog messages, ARIA snapshots) is masked for values the session typed into
  secret fields before it reaches evidence or the AI.
- Triage and explorer prompts pass through redactors (`SecretRedactor`, `PromptRedaction`, `TextRedactor`) that strip
  configured secrets and known key formats.
- Logs use MDC (`run_id`, `agent_id`) and never carry secrets.

**Target safety (rules 8)**
- `TargetPolicy` refuses hosts in `PETEK_PRODUCTION_HOSTS` unless `PETEK_ALLOW_PRODUCTION=true`; the refusal names
  both variables. `petek doctor` and the panel apply the same policy to every URL the operator enters.
- Oracle writes, seeding and teardown work only on companies the test API flags `is_test=true`; the explorer's trial
  touch additionally requires the operator's explicit permission and a confirmed test target.
- The `/test/...` API is addressed only at the configured target (or `PETEK_TEST_API_URL`); redirects are not
  followed, so the token never travels to another host.

**AI containment (rules 2, 3, 6)**
- One structured JSON decision per step, validated in code against the action whitelist (`AgentAction`); anything
  else is rejected. The model never measures time and never evaluates an assertion.
- The Claude CLI runs with `--tools ""`, `--strict-mcp-config`, no settings sources, no session persistence and no
  permission prompts; it is started via `ProcessBuilder` with an argument list (no shell) in a scrubbed environment
  (`CLAUDE_CODE_*` removed except the OAuth token), inside a fresh temporary directory.
- Page content is presented to the model as data with explicit framing; instructions found on pages are never
  executed — the model can only pick a whitelisted action, and the harness executes it.

**Tester isolation (rules 7, 9; docs/requirements/R01 "Isolation guarantees")**
- Every agent has its own browser context (cookies, storage) and its own Playwright instance on a single-thread
  dispatcher; sessions never share objects. JavaScript dialogs are accepted and recorded, never forwarded.
- An agent's runtime knows its colleagues as `Colleague` (name, role, e-mail) — never their password or phone. Values
  the testers share (`company_code`, invite links) are write-once; `{last_id}` never resolves to an object a colleague
  created concurrently; only the admin may create or seed the company (campaign validator).
- Saved storage states (live cookies) are written `rw-------` into a `rwx------` directory, one file per run and agent.
- Injected page scripts (`features/browser/src/main/resources/**/*.js`) blank secret field values before serialising
  DOM snapshots.
- Proven at scale: `TesterIsolationAtScaleTest` (1 000 testers through the real orchestrator on every build, 5 000 in
  CI) and `BrowserIsolationAtScaleTest` (real Chromium contexts, 30 in CI, measured up to 60).
- `PETEK_TEST_API_URL` is judged by the same production-host policy as the target: the test API writes and deletes.

**Panel**
- Ktor CIO bound to `127.0.0.1` only; `Host`/`Origin` headers must be local; every non-GET request needs the
  per-start `X-Petek-Token`; inline scripts carry a nonce; tester e-mails are masked in views.

**Supply chain**
- Dependencies are pinned in `gradle/libs.versions.toml`; the Gradle distribution is checksum-verified; Mailpit is
  pinned and bound to loopback; Kotlin warnings are errors; adding a library requires the owner's approval (rule 11).

## Planned hardening (docs/PLAN.md, Pətək 2)

- Faza 9: provider-neutral key redaction (`sk-`, `AIza`, `gsk_` …); `auto` detection reads host instruction files as
  markers only and never executes their content.
- Faza 10: owner-provided accounts stored as `.env` references, never in the database; a manual-OTP path that keeps the
  code out of logs; capability probe that records CAPTCHA/rate-limit signs instead of fighting them.
- Faza 11: the MCP server on stdio/loopback only; write tools gated by the same `allowWrites` and target policy.
- Faza 12: signed release artifacts; SBOM; `--ci` mode with no interactive secrets.
- Faza 14: correlation id header per agent request (`X-Petek-Correlation-Id`) so the target's own logs can be joined to
  evidence without Pətək reading them.

## Out of scope

Pətək is a testing tool, not a security scanner. It does not attempt exploitation, denial of service or credential
attacks against the target, and it must not be pointed at systems you do not own or have permission to test.
