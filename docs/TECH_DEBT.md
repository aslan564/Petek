# Texniki borc və prioritetlər

`docs/PLAN.md`-nin açıq bəndlərinin prioritetə görə düzülmüş görünüşüdür: hər bəndin tam təsviri PLAN-dadır, burada
yalnız nə olduğu, nəyi gözlədiyi və sırası yazılır. Yeni işə başlamazdan əvvəl bu siyahıya baxılır; bənd bağlananda
PLAN-da `[x]` olur və buradan silinir.

## Yüksək prioritet

**Sahibin qərarı (2026-09-30):** Faza 14-ün və köhnə Faza 8-in açıq bəndləri növbəti işlərin başındadır. Sıra
təklifdir (az asılılıqlı olan əvvəl); sahib dəyişə bilər.

| # | İş | Nə verir | Nə gözləyir |
|---|----|----------|-------------|
| 1 | **Versiyalar arası müqayisə** (regressiya baseline) | Eyni dondurulmuş ssenarinin iki buraxılışdakı nəticəsi yan-yana: nə yeni sındı, nə düzəldi, nə yavaşladı; screenshot fərqi (vizual regressiya); sürət ölçüləri Playwright-ın içindən. | Yeni kitabxana yoxdur (şəkil fərqi JDK ilə). Əlçatanlıq ölçüsü (axe-core) yeni kitabxanadır: qayda 11, sahibin təsdiqi. |
| 2 | **Canlı saytın "yalnız oxu" müşahidəsi** | 2–3 ziyarətçi agent cədvəllə (cron) canlı sayta baxır, heç nə yazmır; pisləşmə olanda xəbər verir. | Yeni kitabxana yoxdur (ziyarətçi run artıq var). Canlı host `PETEK_ALLOW_PRODUCTION` ilə açıq icazə istəyir (qayda 8). |
| 3 | **API adapteri** (brauzersiz test) | Saytın API-sini brauzersiz, eyni sübut və hesabat qaydası ilə yoxlamaq. | Yeni kitabxana yoxdur (Ktor client). |
| 4 | **LLM hakim** (yalnız `LLM_JUDGED`) | Kodun qərar verə bilmədiyi yerdə (məs. mətnin mənası) AI-ın rəyi, ən zəif sübut səviyyəsi kimi. | Qayda 2 ilə sərhəd: kodun hökmünü heç vaxt dəyişmir, yalnız ayrıca işarəli rəy əlavə edir. |
| 5 | **Hazır qoşma paketləri** (Spring Boot starter, Express router, Laravel paketi) | Hədəf sayt `docs/TARGET_CONTRACT.md`-dəki test API-sini bir sətirlə qoşur. | Hər ekosistemdə yeni asılılıq (qayda 11) və sahibin dərc hesabları (Maven Central, npm, Packagist). |
| 6 | **Mobil adapter** (Maestro və ya Appium) | Mobil tətbiqləri eyni orkestrator və hesabatla sınamaq. | Yeni kitabxana (qayda 11), emulyator və ya cihaz. |
| 7 | **Ödənişli modullar** | Bir neçə maşında sürü (Redis/NATS), hesabat tarixçəsi və ortaq panel, SSO və audit, hesabat hostingi, OpenTelemetry adapteri, Pətəkin öz poçt qutusu. | Ayrı repo (ADR-0011); açıq nüvədə yalnız portlar qalır. |

## Müddətli

- **npm dərcini tokensiz yola keçirmək** (2027-ci ilin yanvarına qədər). `release.yml` `npx petek` paketini
  `NPM_TOKEN` (2FA-nı keçən granular token) ilə dərc edir; npm bu cür tokenlə birbaşa dərci 2027-ci ilin yanvarında
  bağlayır. Yol: paket bir dəfə dərc olunur, sahib npmjs.com-da paketin **Settings → Trusted publishing** bölməsində
  GitHub Actions-ı (`aslan564` / `Petek` / `release.yml`) qeyd edir, workflow `id-token: write` icazəsi və npm 11.5.1+
  ilə tokensiz dərc edir, sonra token silinir.

## Adi prioritet

- **Mağazanın üç kartı** (stok yarışı, səbət və giriş, birdəfəlik kupon; Faza 19): universal yazılır və Pətəkin öz
  neytral saxta mağazası ilə (`testing/`, qayda 12) sınanır; sahibin demo mağazası yalnız sahibin öz yoxlamasıdır.
- **Sahibin işləri:** `petek` adının tutulması (GitHub, domen, npm, Maven); `brew`/`scoop` kanalları; demo saytlarda
  sahibin öz AI-ı ilə "Test et" (Faza 22: Pətək onlar haqda heç nə saxlamır, qayda 13); `:e2e:liveTest` (sahibin AI
  kvotası).
- **Hədəf saytın işləri** (Faza 0): test rejimi, test poçtu və OTP, `is_test`, təmizləmə endpointləri, `data-testid`;
  resept `docs/TARGET_CONTRACT.md`-dədir.
