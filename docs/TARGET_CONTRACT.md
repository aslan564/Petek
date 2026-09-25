# Target contract

What a target site offers so Pətək can test it deterministically. KadroHR is the first target. The fake target
(`testing/fake-target`) implements exactly this contract and is used by the e2e tests
(`scenarios/contract-demo.yaml`). Every path and selector can be overridden per campaign under `target_profile:`
(`paths`, `selectors`); the defaults below come from `TargetProfile.DEFAULT_PATHS` / `DEFAULT_SELECTORS`.

The contract is the default, not a requirement: a site whose flows differ describes them under
`target_profile.flows` (sign-up, join by invitation or company code, login, identity check; docs/ARCHITECTURE.md
"Target flows"), with `local_storage`, `dismiss` for overlays and `api_prefix` for its regular API. The flows of §2
below are `TargetProfile.DEFAULT_FLOWS`. `scenarios/kadrohr.yaml` describes the real KadroHR this way.

## 1. Test mode (staging only)

- `TEST_MODE=true` exists only on staging, which has its own database. Production has no test endpoints.
- Outgoing SMTP goes to Mailpit (`:1025`). The SMS provider is off; phone OTPs are read from `GET /test/otp/{phone}`.
- A company created by an `@<PETEK_MAIL_DOMAIN>` owner gets `is_test=true`. Test endpoints and teardown only work on such companies.
- Rate limits and CAPTCHA are off for test IPs (allowlist).
- Every `/test/...` call must carry `X-Test-Token: <PETEK_TEST_TOKEN>`. Otherwise the response is `401`.

## 2. UI flows and `data-testid`s

Selectors are `[data-testid="<id>"]`. Elements that show an object carry `data-id="<object id>"`.

| Flow | Path | Elements (data-testid) | Result |
|---|---|---|---|
| Owner sign-up | `/register` | `register-name`, `register-email`, `register-phone`, `register-password`, `register-company`, `register-submit` | redirect to `/verify?email=…`; an e-mail with a 6-digit code is sent |
| E-mail code | `/verify` | `verify-code`, `verify-submit` | on success either the phone step (below) or the home page |
| Phone OTP (optional) | `/verify/phone` | `verify-phone-code`, `verify-phone-submit` | home page; the code comes from `GET /test/otp/{phone}` |
| Login | `/login` | `login-email`, `login-password`, `login-submit`, `login-error` | home page |
| Join with company code | `/join` | `join-company-code`, `join-name`, `join-email`, `join-phone`, `join-password`, `join-department` (select), `join-submit` | redirect to `/verify?email=…` |
| Accept invitation | `/invite/{token}` (link from the e-mail) | `invite-name`, `invite-phone`, `invite-password`, `invite-submit` | redirect to `/verify?email=…` |
| Session | every page after login | `current-user-name` (the display name), `current-user-role`, `logout` | — |
| Company | `/company` (admin) | `company-name`, `company-code` | — |
| Navigation | header | `nav-home`, `nav-announcements`, `nav-tickets`, `nav-company` | — |
| Notifications | header | `notification-bell`, `notification-count`, `notification-list`, `notification-item` (+`data-id`) | new items appear live |
| Announcements | `/announcements` | `announcement-create` (admin only), `announcement-title`, `announcement-body`, `announcement-submit`, `announcement-item` (+`data-id`), `announcement-body-text` | — |
| Tickets | `/tickets` | `ticket-create`, `ticket-title`, `ticket-description`, `ticket-department` (select), `ticket-submit`, `ticket-item` (+`data-id`) | — |
| Ticket detail | `/tickets/{id}` | `ticket-status`, `ticket-set-in-progress`, `ticket-assignee` (select), `ticket-assign`, `ticket-approve`, `ticket-reject`, `ticket-error` | action buttons appear only for users allowed to use them |

Real-time: the target may use WebSocket, SSE or polling. Pətək does not need to be told. It measures when a text
appears in each receiver's DOM (t1) against the harness emit time (t0). It detects the transport from network traffic
and reports it.

## 3. E-mails (Mailpit)

| E-mail | Subject contains | Body |
|---|---|---|
| Verification | `kod` or `code` | a 6-digit code (Pətək accepts 4–8 digits) |
| Invitation | `dəvət` or `invite` | a link to `/invite/{token}` |

Pətək only reads e-mails received after the run started and marks each one read after use.

Instead of Mailpit, a target in test mode may keep the mail it would send to the test domain and return it from its test
API (`PETEK_MAIL_SOURCE=test-api`): `GET /test/emails?to=<email>` answers newest first
`[{"id", "to", "subject", "text", "html", "links": [], "created_at", "read"}]`, and `POST /test/emails/{id}/read`
marks one used (without that endpoint Pətək remembers used mail itself). A flow's `email_link` may name the link by a
pattern (`set-password\?token=`) when it does not contain one of the hints above.

## 4. Test API (`X-Test-Token`; JSON; snake_case)

| Method | Path | Response |
|---|---|---|
| GET | `/test/otp/{phone}` | `{"phone": "+99450…", "code": "123456"}` or 404 |
| GET | `/test/companies?owner={email}` | `{"id": "c1", "name": "…", "code": "PTK-4821", "is_test": true}` or 404 |
| GET | `/test/companies/{id}` | same shape |
| POST | `/test/companies/seed` | body `{"company_id": "c1", "departments": ["IT", …], "invites": [{"email": "…", "name": "…", "role": "manager", "department": "IT"}]}` → `{"company_id": "c1", "code": "PTK-4821", "departments": {"IT": "d1", …}, "invites": [{"email": "…", "link": "https://…/invite/tok"}]}`. Idempotent per department name and invite e-mail. |
| DELETE | `/test/companies/{id}` | 204; 403 when the company is not `is_test` |
| GET | `/test/announcements/latest?by={email}` | newest announcement created by that user (shape below) or 404 |
| GET | `/test/announcements/{id}` | `{"id": "a1", "title": "…", "body": "…", "status": "published", "created_at": "…", "created_by": "…", "audience": ["…"]}` |
| GET | `/test/announcements/{id}/receipts` | `{"announcement_id": "a1", "receipts": [{"email": "…", "read_at": "…"}]}` |
| GET | `/test/tickets/latest?by={email}` | newest ticket created by that user or 404 |
| GET | `/test/tickets/{id}` | `{"id": "t1", "title": "…", "department": "IT", "status": "open\|in_progress\|approved\|rejected", "assignee": {"email": "…"} \| null, "created_by": "…", "history": [{"from": "open", "to": "in_progress", "by": "…", "at": "…"}]}` |
| GET | `/test/notifications?user={email}` | `{"notifications": [{"id": "n1", "type": "announcement", "object_id": "a1", "created_at": "…", "read_at": null}]}` |

## 5. Regular API used by assertions

`http_status` assertions call the target's normal API with the agent's own session cookies. Example:
`POST /api/tickets/{id}/approve` returns `200` for a manager and `403` for an employee. Approving an already decided
ticket returns `409`. Only one of two concurrent approvals may succeed. The prefix `/api` is
`target_profile.api_prefix` (KadroHR: `/api/v1`); campaign paths may write it as `{api}` (`{api}/tickets/{last_id}/approve`).

## 6. Status of KadroHR

The KadroHR side (test mode, test API, `data-testid`s) lives in its own repository; docs/KADROHR_READINESS.md lists
what it needs. Its real flows differ from §2, so `scenarios/kadrohr.yaml` describes them as flows over its current
markup; `scenarios/contract-demo.yaml` runs against the fake target (`./gradlew :testing:fake-target:run`).
Against a site without the test API, oracle assertions are reported as `SKIPPED`, and flows that need Mailpit or
`/test/otp` cannot finish.
