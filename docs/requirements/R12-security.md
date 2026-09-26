# R12 — Security: secrets never leak, the AI is contained, the panel is local, the supply chain is pinned

**Status:** Implemented (baseline) with a hardening roadmap · **ADRs:** 0004, 0007 · **Policy:** `SECURITY.md`

## Requirement

The owner's words: "təhlükəsizliyi tam təmin olunmalıdır". Pətək handles credentials, drives browsers against real
systems and feeds page content to an AI; none of that may leak a secret, harm a system it was not pointed at, or let
page content or the model steer the tool.

## Architecture

| Threat | Control | Where |
|---|---|---|
| Secret in a log, report or prompt | `Secret` wrapper; configs print set/unset; redactors for triage and explorer prompts; `{self.password}` placeholder; secret-field masking in DOM snapshots and dialog text | `core/security`, `scenarios` `TextRedactor`/`SecretRedactor`, `explorer` `PromptRedaction`, `browser` JS probes |
| Writing to somebody else's site | Site ownership proved by a `/.well-known/petek-verification.txt` file or a `_petek-verification.<host>` DNS TXT record (HMAC of the host with `PETEK_IDENTITY_SECRET`), remembered 30 days; loopback and private addresses exempt; unproved sites are only read; teardown outside the gate by design (ADR-0012) | `features/ownership`, `app` (`RunCommand`, `PanelTargets.owned`, explorer) |
| Wrong target / production | `TargetPolicy` + `PETEK_ALLOW_PRODUCTION`; `PETEK_TARGET` overrides scenario targets; same policy in CLI, doctor, panel | `core/security`, `app` |
| Destructive writes on real data | Oracle and teardown only on `is_test=true`; trial touch needs permission and a confirmed test target; token only to the configured API base, no redirects | `oracle`, `explorer`, `app` |
| Prompt injection from pages | One structured decision validated against the action whitelist; the harness executes; page text is data | `agent`, `llm` |
| AI process escaping | `claude -p` with no tools, no MCP, no settings, no session persistence; `ProcessBuilder` without a shell; scrubbed environment; fresh temp dir; kill tree on timeout | `llm/infrastructure/cli` |
| Panel abuse from another site | Loopback bind; local `Host`/`Origin` only; per-start `X-Petek-Token` on non-GET; script nonce; masked tester e-mails | `dashboard/infrastructure` |
| Cross-session leakage | One browser context and one Playwright per session on its own dispatcher; colleagues known without secrets (`Colleague`); shared values write-once; `{last_id}` never a concurrent colleague's id; admin-only company setup; storage states `rw-------` (see R01 "Isolation guarantees", proven at 5 000 fake and 60 real sessions) | `browser`, `agent`, `orchestration`, `campaign` |
| Dependency compromise | Pinned versions in the catalog; wrapper checksum; Mailpit pinned and loopback; new libraries need approval | `gradle/`, `docker-compose.yml`, rule 11 |

## Verification

- `core`: `Secret` and `TargetPolicy` tests. `app`: `ConfigLoaderTest` ("the printed configuration never shows a
  secret"), `TargetGuardTest`, `DoctorCommandTest` (token never echoed). `llm`: `ClaudeCliLlmClientTest` (argument
  list, environment stripping). `dashboard`: `DashboardServerTest`/`PanelHttpTest` (origin and token checks).
  `browser`: secret-field and dialog masking tests. `scenarios`: redactor tests.

## Hardening roadmap

See `SECURITY.md` "Planned hardening": provider-neutral key redaction (Faza 9), owner accounts as `.env` references
and a manual-OTP path that keeps codes out of logs (Faza 10), MCP on stdio/loopback with the same write gates (Faza
11), signed artifacts and SBOM (Faza 12), correlation ids instead of reading target logs (Faza 14).
