# KadroHR — Pətək üçün hazırlıq siyahısı

Mənbə: 2026-09-25 tarixində `Kadro-Hr-Web` və `KadroSaasHr` repolarının oxunması (heç nə dəyişdirilməyib).
Qərar: Pətək KadroHR-ın **real axınlarına** uyğunlaşır (YAML-da selektorlar və addımlar). KadroHR tərəfində yalnız
aşağıdakı **minimal test rejimi** lazımdır. kadrohr.com hələ müştərisiz olduğu üçün test birbaşa orada aparılır;
**canlıya çıxmazdan əvvəl test rejimi söndürülməlidir.**

## P0 — bunlarsız real run başa çatmır

| # | Nə | Harada (KadroHR) | Niyə |
|---|---|---|---|
| 1 | `TEST_MODE` bayrağı (`kadro.test-mode.enabled`), `PETEK_TEST_TOKEN`, `PETEK_MAIL_DOMAIN` (məs. `test.kadrohr.com`) | `KadroSaasHr` konfiqurasiyası, gateway | Aşağıdakı hər şey yalnız bu bayraqla işləyir |
| 2 | Test domeninə gedən məktubları Resend-ə göndərmək əvəzinə saxlamaq və `GET /test/emails?to=<email>` ilə qaytarmaq (`subject`, `text`, `html`, `links[]`, `created_at`) | `notificationService` `EmailSender.kt` | Pətək təsdiq linkini və dəvəti oxuyur (ayrıca Mailpit serveri lazım olmur) |
| 3 | `companies.is_test` sütunu: sahibinin e-poçtu `@PETEK_MAIL_DOMAIN` ilə bitirsə `true` | `companyService` Flyway migrasiyası, qeydiyyat sagası | Oracle və silmə yalnız test şirkətlərində işləyir |
| 4 | `is_test` şirkətlərə yüksək plan limiti | `subscriptionService` `SubscriptionLimit.kt` | FREE plan: 10 işçi, 5 departament, 5 elan — 30 tester sığmır |
| 5 | `X-Test-Token` düzgün olan sorğulara rate-limit tətbiq olunmasın | `apiGateway` `KadroRateLimitFilter.kt` | Bir IP-dən dəqiqədə 50 auth sorğusu limiti qeydiyyatı kəsir (429) |
| 6 | Test API (`/test/**`, yalnız `TEST_MODE`, `X-Test-Token` sabit-vaxt yoxlaması, yoxdursa 401) | gateway + daxili controllerlər | Hakimin "C" mənbəyi, setup və təmizlik |

Test API endpointləri (JSON, snake_case):

| Metod | Yol | Qaytarır |
|---|---|---|
| GET | `/test/emails?to=` | test domeninə göndərilmiş son məktublar |
| GET | `/test/companies?owner=` , `/test/companies/{id}` | `id, name, code (xam şirkət kodu), is_test` |
| POST | `/test/companies/seed` | departamentlər (ada görə idempotent) + dəvətlər (`CreateEmployeeService` ilə); dəvət linklərini qaytarır |
| DELETE | `/test/companies/{id}` | test şirkətinin bütün servislərdəki datası (yalnız `is_test`, əks halda 403) |
| GET | `/test/announcements/latest?by=` , `/test/announcements/{id}` , `/test/announcements/{id}/receipts` | `announcements` + `announcement_reads` (e-poçt + `read_at`) |
| GET | `/test/leave-requests/latest?by=` , `/test/leave-requests/{id}` | status + tarixçə (yarış testi üçün) |
| GET | `/test/tickets/latest?by=` , `/test/tickets/{id}` | status + `ticket_status_history` |
| GET | `/test/notifications?user=` | `notifications` cədvəli |

## P1 — etibarlılığı artırır (Pətək bunlarsız da işləyir, amma daha kövrək olur)

- Əsas elementlərə `data-testid`. Siyahı: `docs/TARGET_CONTRACT.md` §2. `KadroTextField`/`KadroPasswordField` prop-ları ötürür, ona görə əlavə etmək ucuzdur.
- Test rejimində açılan pəncərələr: domen dialoqu (Pətək `localStorage['kadro:domain_dialog_dismissed']='1'` qoyur), ConsentGate (test istifadəçiləri üçün öncədən qəbul), ChatWidget (gizlət).
- `is_test` şirkətlərində `register-employee` üçün admin təsdiqini (`PENDING_APPROVAL`) keçmək və qeydiyyat rejimini HYBRID etmək (şirkət kodu ilə qoşulma üçün).
- Qərar gözləyən məzuniyyət sorğusunu ikinci dəfə təsdiqləmək indi 422 qaytarır. Kontraktda 409 nəzərdə tutulub, amma bu məcburi deyil: Pətək-in yarış testi yalnız birinin qalib gəldiyini yoxlayır.

## Pətək tərəfi (bizdə edilir)

- [x] `scenarios/kadrohr.yaml` KadroHR-ın real axınlarına görə yazıldı (`target_profile.flows`):
  - `/register` (ad/soyad, şifrə təkrarı, ölkə, HYBRID qeydiyyat rejimi);
  - linklə təsdiq (`registration/verify?token=`), sonra `/register/verify` → `/register/complete`;
  - şirkət kodu ilə login (kod test API-dən, `{shared.company_code}`);
  - `/register/employee` (şirkət kodu, linklə təsdiq);
  - dəvət linki `set-password?token=` (backend forması `#password`, `#confirmPassword`, `#submitBtn`);
  - elanlar (qəbz test API-dən) və yarış testi üçün ticket əvəzinə məzuniyyət təsdiqi (iki menecer, bir qalib).
- [x] Selektorlar `autocomplete`, id və Playwright rol selektorları ilə seçilir; `data-testid` gələndə
  `target_profile.selectors`-da açarın dəyərini dəyişmək kifayətdir, axınlar açar adı ilə yazılıb.
- [x] Domen dialoqu `local_storage` ilə (`kadro:domain_dialog_dismissed=1`, dil `kadro:lang=az`) açılmır; GDPR
  razılıq pəncərəsi `dismiss` ilə bağlanır.
- [x] Qeydiyyat və login tempi tənzimlənir (`campaign.pacing.start_stagger_ms: 1500`), IP limitinə düşməmək üçün.
- [x] Test poçtu hədəfin test API-sindən oxuna bilər (`TestApiMailbox`, `GET /test/emails?to=`).
- [x] App konfiqurasiyası: `PETEK_MAIL_SOURCE=mailpit|test-api` və test API-nin baza ünvanı `PETEK_TEST_API_URL` (API
  `api.kadrohr.com`-dadır, sayt `kadrohr.com`) — composition root-da qoşulub; `petek doctor` seçilmiş poçt mənbəyini yoxlayır.
- [ ] `petek probe https://kadrohr.com` hazırlığı yoxlayır: səhifələr, selektorlar, `/test` API, real-time transportu.

**Pətək-in KadroHR-dan gözlədiyi əlavə cavablar** (docs/PLAN.md "Real KadroHR üçün açıq suallar"):

- `http_status` yoxlaması (`POST /api/v1/leave-requests/{id}/approve` → 403) agentin cookie-ləri ilə sayt origin-inə
  gedir; KadroHR isə token-i `Authorization` başlığı ilə `api.kadrohr.com`-a göndərir. Test rejimində API-nin
  `kadrohr.com/api/...` altında cookie ilə açılması (və ya başqa həll) lazımdır, əks halda bu yoxlama 401 görür.
- `/test/emails` cavabında `links[]` sahəsi olsun (dəvət və təsdiq linkləri), `POST /test/emails/{id}/read` olmasa da
  olar.
- `/test/leave-requests/{id}` cavabında `status` (`APPROVED` və s.) və tarixçə; `/test/announcements/{id}`-də `title`.

## Şəbəkə

Bu maşının şəbəkəsində korporativ firewall `*.kadrohr.com`-u "grayware" kimi bloklayır. Real run icazəli şəbəkədən və ya IT-nin icazəsindən sonra işlədilməlidir.
