# Pətək

**Pətək** çoxistifadəçili AI test platformasıdır. Bir veb tətbiqin üzərinə eyni anda çoxlu AI tester agenti
buraxır — hər biri öz təcrid olunmuş brauzer sessiyasında — onları real istifadəçi komandası kimi koordinasiya edir
(biri elan verir, iyirmi doqquzu onu görməlidir; iki nəfər eyni sorğunu eyni anda təsdiqləyir; işçi adminin
səhifəsini açmağa çalışır), baş verənləri modelin toxunmadığı saatla ölçür və hər hökmün sübutla — screenshot, DOM
oxunuşu, şəbəkə mübadiləsi və ya hədəfin öz test API-sinin cavabı ilə — dəstəkləndiyi hesabat yazır.

English: [README.md](README.md).

- **Məhsuldur, prompt deyil.** Pətək öz veb paneli, brauzer parkı, sübut bazası və hesabatı olan işləyən proqramdır.
  AI onun içində bir komponentdir, əksi yox.
- **Öz AI-ını gətir, hansı olursa olsun.** Pətək heç bir AI vendoruna bağlı deyil: agentlər sizdə olan AI ilə
  düşünür, login olduğunuz istənilən AI CLI (`.env`-də təsvir olunur, Codex, Gemini, OpenCode isə adla tapılır) və ya
  istənilən OpenAI-uyğun API (OpenAI, Grok, OpenRouter, Ollama, ...). Biri işləməsə, tapılan növbətisi cavab verir.
  Pətək model xərcini daşımır və məlumatınızı ikinci dəfə görmür.
- **Qərarı kod verir, model yox.** Vaxtı harness ölçür, assertləri kod yoxlayır, agent yalnız kodda yazılmış
  whitelist-dən hərəkət seçə bilər. Hansı AI-ın işlədiyi hökmün dəyərini dəyişmir.
- **Sizin istənilən saytınız.** Pətək heç bir konkret sayt üçün yazılmayıb: sayt data kimi təsvir olunur
  (`target_profile`), mühərrik heç bir saytı, şirkəti və ya sahəni adı ilə tanımır.

Müəllif: **Aslan Aslanov** · © 2026 **Kodcraft** · [Apache License 2.0](LICENSE) ilə açıq mənbədir.

---

## Mündəricat

1. [Niyə](#niyə)
2. [Necə işləyir](#necə-işləyir)
3. [Nə var](#nə-var)
4. [Sürətli başlanğıc](#sürətli-başlanğıc)
5. [Öz saytınızda istifadə](#öz-saytınızda-istifadə)
6. [Konfiqurasiya](#konfiqurasiya)
7. [Ssenarilər](#ssenarilər)
8. [Hədəf kontraktı](#hədəf-kontraktı)
9. [Veb panel](#veb-panel)
10. [Arxitektura](#arxitektura)
11. [Təhlükəsizlik](#təhlükəsizlik)
12. [Yol xəritəsi](#yol-xəritəsi)
13. [Sənədlər](#sənədlər)
14. [İnkişaf](#inkişaf)
15. [Lisenziya, ticarət nişanı və müəllif hüququ](#lisenziya-ticarət-nişanı-və-müəllif-hüququ)

---

## Niyə

Çoxistifadəçili və real-time xətalar bir adam saytı gəzəndə üzə çıxmır: alanların yarısına çatmayan elan, iki
menecerin eyni anda dəyişdiyi ticket statusu, birbaşa URL ilə keçilən icazə yoxlaması. Bunu əllə test etmək hər
buraxılışda otuz adam və otuz cihaz istəyir. Pətək adamları agentlərlə, cihazları təcrid olunmuş brauzer
kontekstləri ilə əvəz edir, testləri versiyalanmış ssenarilər kimi saxlayır və hər run-ı developerin üstündə işləyə
biləcəyi sübuta çevirir.

## Necə işləyir

```
┌──────────────────────────────────────────────────────────────┐
│ Ev sahibi AI (kod agentiniz: Codex / Gemini CLI / Cursor ...) │  rollar: kəşfiyyatçı, ssenari müəllifi, hakim, kök səbəb
│  Pətəkin skill paketini oxuyur, MCP / --json ilə çağırır       │  (yol xəritəsi Faza 11–12)
└───────────────┬──────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────┐
│ Pətək mühərriki + panel (deterministik, sübut əsaslı)         │  explore · plan · run · report · teardown
│  kimliklər · brauzer parkı · hadisə şini · saat · assertlər   │  hədəf profilləri · poçt/OTP mənbələri · oracle
│  sübut bazası (SQLite + fayllar) · üç mənbəli hakim · hesabat │
└───────────────┬──────────────────────────────────────────────┘
                │ LlmClient (strukturlu cavab, whitelist hərəkətlər)
┌───────────────▼──────────────────────────────────────────────┐
│ Sürü beyni: N tester agentinin `do` addımları                  │  layihənizin öz AI-ı, agent başına ölçülür
└──────────────────────────────────────────────────────────────┘
```

Bir run (`petek run scenarios/<kampaniya>.yaml`):

1. **Kimliklər.** Orkestrator N deterministik tester yaradır (adlar, test domenində e-poçtlar, sirrin HMAC-ı ilə
   parollar, telefonlar, rollar, departamentlər, qeydiyyat rejimi). Agent öz kimliyini yalnız oxuyur.
2. **Brauzerlər.** Chromium serverləri yükə görə bölünərək qaldırılır; hər agent bir təcrid olunmuş kontekst alır, hər
   sessiyanın bütün Playwright çağırışları öz tək-thread dispetçerində icra olunur.
3. **Addımlar.** Deterministik `run` addımları (qeydiyyat, login, dəvətlə və ya şirkət kodu ilə qoşulma) hədəfin
   axınlarını data kimi icra edir; `do` addımları AI-dan bir dəfəyə bir whitelist hərəkəti istəyir, səhifənin
   nömrələnmiş snapshot-u ilə. `emits` harness saatında t0 qoyur; hər alanda `wait_for` DOM-da t1 ölçür.
4. **Yoxlama.** Tipli assertlər (`visible_text`, `not_visible`, `count`, `latency_max`, `http_status`,
   `only_one_succeeds`, `oracle`) kodla yoxlanır. Üç mənbəli hakim göndərənin etdiyini (A), alanın gördüyünü (B) və
   test API-nin dediyini (C) tutuşdurur; tapıntılar backend / çatdırılma-UI / araşdırılmalı kimi sinifləndirilir.
5. **Hesabat və təmizlik.** Markdown + HTML hesabat: addımlar, screenshot-lar, gecikmə paylanması, A/B/C ilə
   tapıntılar, uğursuz agentlər, `--repeat` üzrə stabillik, agent başına token və xərc. Test məlumatı test API ilə,
   yalnız `is_test` şirkətlərində silinir.

## Nə var

| Sahə | Bu gün |
|---|---|
| CLI | `panel` (default), `init`, `verify`, `doctor`, `dev`, `capacity`, `plan`, `smoke`, `run --repeat N --testers N --ci --swap-accounts`, `report`, `findings`, `teardown`, `probe`, `mcp` |
| Veb panel | Təlimat, Kəşfiyyat, Ssenarilər (draft → təsdiq → dondurma, diff, triaj), Orkestrator tapşırıq matrisi, canlı Agentlər lövhəsi, Hesabatlar və stabillik |
| Kəşfiyyatçı | Sayt modelini üç fazada öyrənir (səhifələr, formalar, əməliyyatlar, rollar, realtime, naməlumlar), sahibə sual verir, test ideyaları çıxarır, kampaniya layihəsi yazır |
| Triaj | Run-ın sürprizlərini sistem bug / model boşluğu / ssenari xətası kimi ayırır və ssenari v2-ni diff kimi təklif edir |
| Hədəflər | Sizə məxsus istənilən sayt; nümunələr `docs/examples/`-də, e2e üçün fake kontrakt saytı (`testing/fake-target`) |
| Poçt / OTP | Mailpit catch-all qutusu və ya hədəfin test API-si (`PETEK_MAIL_SOURCE`); telefon OTP test API-dən |
| AI | Sizdə olan hansı olursa (`PETEK_LLM_PROVIDER=auto`): istənilən AI CLI (`PETEK_LLM_BIN` + `PETEK_LLM_ARGS`), Codex, Gemini və ya OpenCode CLI, Anthropic API və ya istənilən OpenAI-uyğun endpoint (OpenAI, Grok, OpenRouter, Ollama, LM Studio, vLLM) — retry, paralellik limiti, ölçmə və tapılan növbəti AI-a keçidlə bir `LlmClient` portu arxasında; `doctor` hansını və niyə seçdiyini deyir |
| Sübut | SQLite (run, kimlik, addım, hadisə, qəbz, assert, tapıntı, istifadə) + artefakt faylları, hər qeydin ID-si var |
| Keyfiyyət qapıları | Kotlin warning = error, Spotless ilə ktlint, məcburi lisenziya başlıqları, Konsist arxitektura testləri, Kover, real Chromium ilə e2e |
| İzolyasiya | Hər tester öz brauzer kontekstində və öz thread-ində; kolleqalarını sirlərsiz tanıyır; paylaşılan dəyərlər write-once; hər build-də 1 000 testerlə (orkestrator), CI-da 5 000 tester və 30 real Chromium sessiyası ilə (60-a qədər ölçülüb) sübut olunur — [R01](docs/requirements/R01-concurrent-multi-agent-testing.md) |

## Sürətli başlanğıc

**Beş dəqiqəyə, tətbiqinizin yanında.** Node.js ilə: `npx petek init --target https://staging.my-site.com` (`.env`,
`.petek/`, skill paketi və AI-nızın MCP qeydi), `npx petek verify` (staging saytın sizin olduğunu təsdiqləyir), sonra
`npx petek dev`: tətbiqin health ünvanını (`.petek/petek.yaml`-da `health_url`) gözləyir və paneli yanında açır. AI
layihənizin artıq istifadə etdiyidir (`PETEK_LLM_PROVIDER=auto`).

**Buraxılışdan (build də, JDK də lazım deyil).** [Releases](https://github.com/aslan564/Petek/releases) səhifəsindən
maşınınıza uyğun bundle-ı endirin: `petek-<versiya>-linux-x64.tar.gz`, `-linux-arm64.tar.gz`, `-mac-arm64.tar.gz` və ya
`-win-x64.zip` (hər biri öz Java runtime-ını və Chromium driver-ini daşıyır; `SHA256SUMS` yoxlama cəmləridir), başqa
maşın üçün `PATH`-də JDK 25 ilə işləyən `petek-<versiya>-any-jdk25.zip`. İstənilən yerə açın; hansı olursa olsun bir AI
olsun: login olduğunuz AI CLI və ya OpenAI-uyğun bir xidmətin API açarı (bax `.env.example`). Chromium-u Playwright ilk
istifadədə özü yükləyir.

```bash
tar xzf petek-0.2.0-linux-x64.tar.gz && cd my-site     # istənilən qovluq: Pətək saytın yanında işləyir, build-inin içində yox
cp ../petek-0.2.0-linux-x64/.env.example .env          # PETEK_TARGET doldurun (+ tam run üçün PETEK_TEST_TOKEN, PETEK_IDENTITY_SECRET)
../petek-0.2.0-linux-x64/bin/petek doctor              # hədəf siyasəti, hədəf, Chromium, poçt qutusu, test API, AI
../petek-0.2.0-linux-x64/bin/petek panel               # veb paneli açır: http://127.0.0.1:7070
```

Windows-da başladıcı `bin\petek.cmd`-dir. Əlavə JVM seçimləri `PETEK_OPTS`-a yazılır.

**Node.js ilə (`npx`).** `petek` npm paketi yalnız başladıcıdır: yuxarıdakı bundle-ı bir dəfə
`~/.petek/versions/<versiya>`-ya endirir, yoxlama cəmini yoxlayır və işə salır; başqa heç nə qurulmur.

```bash
cd my-site
npx petek init --target https://staging.my-site.com   # .env, .petek/, skill paketi + AI agentiniz üçün MCP qeydi
npx petek doctor
npx petek panel
```

**Docker ilə.** `ghcr.io/aslan564/petek:<versiya>` (linux/amd64 və linux/arm64) Linux bundle-ının Playwright-ın rəsmi
image-i üstündə qurulmuş halıdır: Chromium və kitabxanaları içindədir; layihəni `/work` kimi mount edin (`.env`,
`scenarios/`; `evidence/` ora yazılır). Panel yalnız loopback-ə bağlanır, ona görə `--network host` lazımdır (Linux);
CI əmrlərdən və `--json`-dan istifadə edir. Test olunan layihə üçün hazır workflow: `docs/ci/github-actions.yml`.

```bash
docker run --rm -v "$PWD:/work" --env-file .env ghcr.io/aslan564/petek:0.2.0 doctor
docker run --rm -v "$PWD:/work" --env-file .env ghcr.io/aslan564/petek:0.2.0 --json run scenarios/my-site.yaml
docker run --rm -v "$PWD:/work" --env-file .env --network host ghcr.io/aslan564/petek:0.2.0 panel --no-open
```

**Mənbədən.** Tələblər: Gradle-ı işlətmək üçün JDK 21+ (build öz JDK 25 toolchain-ini yükləyir) və eyni AI; Docker
yalnız e-poçt kodları Mailpit ilə gələndə lazımdır (`docker compose up -d`, `PETEK_MAIL_SOURCE=mailpit`).

```bash
git clone https://github.com/aslan564/Petek.git && cd Petek
./gradlew :app:run                                     # .env hələ yoxdur: brauzer hansı saytın test olunacağını soruşur, sonra panel açılır
./gradlew :app:run --args="doctor"                     # hədəf siyasəti, hədəf, Chromium, poçt qutusu, test API, AI
```

Pətək yalnız sizin verdiyiniz saytı test edir. `.env` olmayanda `petek panel` bir sualı olan səhifə açır — hansı sayt
test olunsun — və cavab gələnə qədər başqa heç nə başlatmır: ünvan cavab verməlidir (işləməyən, bloklanan və ya yalnız
CDN-in xəta səhifəsini göstərən sayt səbəbi ilə rədd edilir), sonra `.env.example`-dən `.env`-ə yazılır (test API
tokeni, IMAP və ya Mailpit üçün bu faylı sonra redaktə edin) və panel həmin sayt üçün açılır. `cp .env.example .env`
edib əl ilə doldurmaq da olar. `.env` olmayanda MCP serveri host AI-a sizdən soruşmağı deyir. Cavab verməyən sayt
(işləmir, bloklanıb, ünvan səhvdir) heç bir tester başlamazdan əvvəl olduğu kimi bildirilir, başqa bir şeylə əvəz
edilmir; heç bir ekran və ya nəticə uydurulmur. Pətəkin öz kontrakt saytı
(`testing/fake-target`, e2e dəstinin əvəzedicisi) Pətəkin özünü inkişaf etdirmək üçündür və yalnız açıq
konfiqurasiya ilə açılır:

```bash
./gradlew :testing:fake-target:run                     # http://127.0.0.1:18080, poçt API :18025
./gradlew :app:run --args="--env-file .env.fake-target doctor"
./gradlew :app:run --args="--env-file .env.fake-target run scenarios/contract-demo.yaml --testers 6"
```

Komanda sətri ilə, başdan sona:

```bash
./gradlew :app:run --args="capacity"                   # bu maşın neçə tester götürər (tövsiyə, limit deyil)
./gradlew :app:run --args="plan docs/examples/company-portal.yaml" # yaradılacaq kimliklər, heç nə icra olunmur
./gradlew :app:run --args="run docs/examples/company-portal.yaml --repeat 3"
./gradlew :app:run --args="report latest"
./gradlew :app:run --args="teardown --run <run_id>"    # test şirkətini sil (hər run-ın sonunda da edilir)
```

Çıxış kodları: `0` uğur, `1` tapıntı var, `2` konfiqurasiya xətası və ya dayandırılmış run, `130` kəsildi.

## Öz saytınızda istifadə

Pətək **kitabxana deyil, yanaşı işləyən alətdir (sidecar)**: onu saytınızın Maven, npm və ya Composer build-inə əlavə
etmirsiniz. Alət kimi qurulur (buraxılış bundle-ı və ya `npx petek`, bax R15), saytın yanında işə salınır və saytın
URL-inə yönəldilir. `petek init` layihəni bir addımda hazırlayır: şablondan `.env` (bir daha toxunulmur),
`.petek/petek.yaml` profili, `.petek/SKILL.md` skill paketi (rollar: kəşfiyyatçı, ssenari müəllifi, hakim, kök səbəb),
repoda aşkarladığı AI kod agentləri üçün (və ya `--ai agents,cursor,gemini,copilot|all`) təlimat faylında işarəli
parça (kod agentlərinin çoxunun oxuduğu `AGENTS.md`, `.cursor/rules/petek.mdc`, `GEMINI.md`,
`.github/copilot-instructions.md`; əlavə olunur, təkrar çağırışda yenilənir, sizin mətninizin üstünə yazmır) və
layihənin MCP faylında `petek` serveri (`.mcp.json`, `.cursor/mcp.json`, `.gemini/settings.json`, `.vscode/mcp.json`).
`.env` və `evidence/` `.gitignore`-a düşür. **Sizin öz AI login-inizi** işlədir — BMAD layihədəki köməkçini necə
işlədirsə, elə: login olduğunuz AI alətini (və ya verdiyiniz API açarını) çağırır, modeli sizin planınız ödəyir; Pətək
müəlliflərinə heç nə göndərilmir.

Saytınızdan nə tələb olunur — testin dərinliyinə görə:

| İstədiyiniz | Saytınıza lazım olan | Necə |
|---|---|---|
| Yalnız oxu kəşfiyyatı: səhifələr, formalar, əməliyyatlar, sayt modeli, test ideyaları, ssenari qaralaması | Heç nə. Anonim səhifələr ziyarətçi kimi oxunur. | `.env`-də `PETEK_TARGET=https://saytiniz`, sonra `petek panel` → **Kəşf et** (yazma qutusu boş) |
| Rollarla, daxil olmuş kəşfiyyat | Verə biləcəyiniz hesablar, və ya Pətək-in tamamlaya biləcəyi özü-qeydiyyat | Qeydiyyat axını (e-poçt kodu üçün test poçtu, telefon OTP test API-dən) və ya sahibin verdiyi loginlər (Faza 10) |
| Tam kampaniyalar: 30 tester, qeydiyyat, OTP, assertlər, real-time yoxlamalar, teardown | [Hədəf kontraktı](docs/TARGET_CONTRACT.md): `X-Test-Token` arxasında `/test/...` API, `is_test` şirkətlər, catch-all poçt (Mailpit) və ya `GET /test/emails`; `data-testid`-lər faydalıdır, məcburi deyil | `PETEK_TEST_TOKEN`, `PETEK_MAIL_SOURCE`, `PETEK_IDENTITY_SECRET` doldurulur; `petek doctor` tam yaşıl olmalıdır |
| Production host | Açıq icazə `PETEK_ALLOW_PRODUCTION=true` (`PETEK_PRODUCTION_HOSTS`-dakı hostlar əks halda rədd edilir) | Yalnız kontraktı danışan staging ilə, və ya yalnız oxu |

**Yalnız öz saytınız, yalnız test hesabları.** Pətək (run-lar, qeydiyyatlar, kəşfiyyatçının rolları və sınaq toxunuşu)
yalnız sahibliyi təsdiqlənmiş sayta yazır: `petek verify` bir kod verir, onu ya `/.well-known/petek-verification.txt`
faylı, ya da `_petek-verification.<host>` DNS TXT qeydi kimi dərc edirsiniz (hər ikisində `petek-verification=<kod>`),
sonra yoxlayır. Kod dəqiq host üçündür (kiçik hərflə): `www.example.com` və `example.com` iki ayrı hostdur; fayl həmin
origin-dən başqa hosta yönləndirmə olmadan verilməlidir. Sübut 30 gün yadda qalır, `petek verify` onu yenidən yoxlayır.
`localhost`, loopback və daxili şəbəkə ünvanları təsdiq istəmir. Təsdiqlənməmiş sayt yalnız anonim ziyarətçi kimi
oxunur; `petek verify` sübut yoxdursa 1, `petek run` belə saytı rədd edəndə 2 kodu ilə çıxır. Pətəki öz pre-production
və ya staging nüsxənizə yönəldin, yalnız test hesabları işlədin, real istifadəçinin hesabını heç vaxt verməyin (ADR-0012).

Sonra dövrə hər sayt üçün eynidir: `doctor` → `panel` → kəşf et → kəşfiyyatçının suallarına cavab ver → qaralamanı
ssenarilərə göndər → təsdiqlə → run → hesabat → tapıntıların sübutlarını (`FindingBundle`, Faza 11) öz AI-nizə oxut
və səbəbi kodunuzda düzəlt.

## Konfiqurasiya

Hər şey `.env`-dən (və ya `--env-file`) və mühitdən gəlir; real mühit dəyişənləri üstün gəlir. Sirlər `Secret` ilə
gəzir, loga və AI-a düşmür. Yalnız `PETEK_TARGET` məcburidir.

| Açar | Default | Məna |
|---|---|---|
| `PETEK_TARGET` | — | Test olunan sistem; hər kampaniyanın `campaign.target`-ini əvəz edir |
| `PETEK_TARGETS_DIR` | `targets` | Hədəf profilləri, hər sayta bir `targets/<ad>.yaml` (URL, `api_url`, production hostlar, poçt, `${VAR}` token və hesab referansları, giriş sırası, kampaniya profili); `PETEK_TARGET` profilin adı ola bilər, panel profili olan istənilən saytda run edir (bax `docs/examples/target-profile.yaml`) |
| `PETEK_PRODUCTION_HOSTS` | — (yoxdur) | Hədəf kimi rədd edilən hostlar, əgər … |
| `PETEK_ALLOW_PRODUCTION` | `false` | … bu `true` deyilsə (qayda 8) |
| `PETEK_TEST_TOKEN` | — | Hədəfin `/test/...` API-si üçün `X-Test-Token`; boş = oracle yoxlamaları və teardown yoxdur |
| `PETEK_TEST_API_URL` | hədəf | `/test/...` API hədəfin origin-ində deyilsə onun baza ünvanı |
| `PETEK_MAIL_INBOX` | — | Sizin qutunuz (`test@sirket.az`): hər tester `test+<run>-<agent>@sirket.az` ilə qeydiyyatdan keçir; `PETEK_MAIL_DOMAIN`-i əvəz edir; `+`-u qəbul etməyən sayt hesabatda deyilir |
| `PETEK_IMAP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_TLS` / `_FOLDER` | — / 993 / qutu / — / `true` / `INBOX` | `imap` mənbəyi o qutunu necə oxuyur (Jakarta Mail/Angus); parol `Secret`-dir |
| `PETEK_MAIL_SOURCE` | `mailpit` | `mailpit`, `test-api` (`GET /test/emails`, token lazımdır), `imap` (öz qutunuz) və ya `manual` (hər kodu paneldəki "Kodu daxil et" pəncərəsinə özünüz yazırsınız; kəşfiyyatçının 1–3 sessiyası üçün) |
| `PETEK_MAILPIT_URL` | `http://localhost:8025` | Mailpit API |
| `PETEK_MAIL_DOMAIN` | `petek.test` | Test kimliklərinin e-poçt domeni |
| `PETEK_IDENTITY_SECRET` | `~/.petek/identity.secret` | Test parollarının derivasiyası **və** `petek verify`-ın verdiyi sahiblik kodunun açarı (≥ 16 simvol). Eyni saytı test edən hər maşında eyni olsun: başqa açar başqa kod verir və dərc olunmuş sübut artıq uyğun gəlmir |
| `PETEK_LLM_PROVIDER` | `auto` | `auto`, `cli`, `codex-cli`, `gemini-cli`, `opencode-cli`, `anthropic-api`, `openai-compat`, `none`; `auto` mühitdəki ayar və açarlara, layihənin AI işarəsinə (`AGENTS.md`, `GEMINI.md`) və `PATH`-dakı agent CLI-lərinə görə seçir, qalanlarını ehtiyat saxlayır, heç nə tapmasa sizin yerinizə vendor seçmir, `doctor` səbəbini deyir |
| `PETEK_LLM_MODEL` | alətin öz modeli | Model; boş olanda alət və ya provayder necə qurulubsa o işləyir; `anthropic-api` və `openai-compat` üçün məcburidir |
| `PETEK_LLM_BIN` | — | İşlədiləcək AI CLI (`cli`), və ya `codex-cli`, `gemini-cli`, `opencode-cli` üçün başqa binar |
| `PETEK_LLM_ARGS` | — | Yalnız `cli`: onun arqumentləri, `{model}`, `{effort}`, `{system}`, `{schema}`, `{schema_file}` ilə; söhbət STDIN-ə gedir |
| `PETEK_LLM_ENV_UNSET` | — | AI alətinin mühitindən çıxarılan dəyişənlər, `NAME` və ya `PREFIX*` |
| `PETEK_LLM_BASE_URL` | — | OpenAI-uyğun endpoint: OpenAI, Ollama (`http://localhost:11434/v1`), Groq, Mistral, OpenRouter, LM Studio |
| `PETEK_LLM_API_KEY` | — | `anthropic-api` / `openai-compat` açarı; `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` alias-dır |
| `PETEK_LLM_STRUCTURED` | `schema` | `schema`, `json_object`, `prompt`; rədd edilən rejim özü bir pillə aşağı düşür |
| `PETEK_LLM_EFFORT` | dəstəkləyəndə `low` | Düşünmə səviyyəsi (Codex CLI, `cli`-nin `{effort}`-u; `openai-compat`-da `reasoning_effort`) |
| `PETEK_LLM_CONCURRENCY` | `6` | Bütün agentlər üzrə eyni anda AI çağırışı (1–64) |
| `PETEK_LANGUAGE` | `auto` | AI-ın sizin üçün nə dildə yazdığı (kəşfiyyatçının sualları və ideyaları, testerlərin xülasələri, triaj): `auto` sizin öz təlimat və ssenarilərinizin dilini izləyir, ya da `English` kimi ad |
| `PETEK_BROWSER_HEADLESS` | `true` | `run --headful` bunu üstələyir |
| `PETEK_BROWSER_TOPOLOGY` | `shared-server` | və ya `per-session` |
| `PETEK_BROWSER_IGNORE_TLS_ERRORS` | `false` | etibarsız sertifikatları qəbul et (self-signed staging, trafiki yenidən imzalayan proxy); `doctor` açıq olduğunu göstərir |
| `PETEK_CORRELATION_HEADER` / `PETEK_TRACE_LOG` | `false` / — | Testerlər `X-Petek-Correlation-Id` göndərir (CORS icazə verməlidir); saytın logunda tapıntının ID-si olan sətirlər paketinə düşür (`petek findings`, MCP `get_finding_bundle`) |
| `PETEK_TELEMETRY` | `off` | `local`: yalnız sayğaclar (AI çağırışları, tokenlər, provayder və tester qrupuna görə run-lar) `evidence/telemetry/usage.jsonl`-a; məzmun yoxdur, heç yerə göndərilmir |
| `PETEK_EVIDENCE_DIR` / `PETEK_DB` | `evidence` / `evidence/petek.db` | Sübut, hesabat, log və bazanın yeri |

## Ssenarilər

Kampaniya YAML faylıdır: testerlər kimdir, necə qeydiyyatdan keçir, nə edir və nə doğru olmalıdır. Deterministik
olan hər şey `run` addımıdır (hədəfin axınlarından kod icra edir); yalnız mühakimə tələb edən `do` addımıdır.

```yaml
campaign:
  name: contract-demo
  testers: 30
  roles: {admin: 1, manager: 5, employee: 24}
  departments: [IT, HR, Satış, Maliyyə, Əməliyyat]
  registration: {invite: 15, company_code: 14}
  budget: {max_steps_per_agent: 60, max_minutes: 40}
setup:
  - {id: owner_signup, actor: admin, run: register_owner}
  - {id: seed,         actor: admin, run: seed_company}
  - {id: join,         actor: "employee[*] | manager[*]", run: register_and_login}
steps:
  - id: announce
    actor: admin
    do: "'Pətək test' başlıqlı elan dərc et"
    emits: announcement_created
  - id: receive
    actor: "employee[*] | manager[*]"
    wait_for: announcement_created
    assert:
      - {visible_text: "Pətək test", timeout_s: 30}
      - {latency_max: 5000}
```

`target_profile` saytın özünü təsvir edir: yollar, selektorlar, qeydiyyat/login axınları, bağlanacaq overlay-lər, API
prefiksi və yaradılan ID-lərin haradan oxunduğu. `docs/examples/company-portal.yaml` axınları kontraktdan fərqlənən
saytı tamamilə bu yolla (kodsuz) təsvir edir, `scenarios/contract-demo.yaml` kontrakt saytını. Tam format, aktor qrammatikası, şablonlar və assert
növləri: [docs/PLAN.md](docs/PLAN.md) ("Ssenari formatı") və [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Hədəf kontraktı

Pətək ən yaxşı hədəf **test rejimi** verəndə işləyir: `X-Test-Token` arxasında `/test/...` API (OTP kodları,
şirkətlər, seeding, oxunma qəbzli elanlar, ticketlər, bildirişlər, isteğe bağlı poçt), `is_test` şirkətlər və sabit
`data-testid`-lər. [docs/TARGET_CONTRACT.md](docs/TARGET_CONTRACT.md) bunu müəyyən edir; `testing/fake-target` icra
edir. Test API olmadan səhifə və şəbəkə
yoxlamaları yenə işləyir, oracle yoxlamaları buraxılır, teardown mümkün olmur (yol xəritəsi bunu sübut səviyyələri
ilə birinci dərəcəli rejim edir).

## Veb panel

Arqumentsiz `petek` (və ya `petek panel`) yalnız loopback-ə bağlanan Ktor serveri qaldırır və brauzeri açır. Ekranlar:
**Təlimat** (hədəf, sadə dildə təlimat, komanda, büdcə, "Kəşf et"), **Kəşfiyyat** (kəşfiyyatçı canlı: fazalar, sayt
modeli, tapıntılar, cavablanacaq suallar), **Ssenarilər** (versiyalar, YAML, diff, təsdiq/dondurma, triaj),
**Orkestrator** (addım zolaqları × agentlər matrisi, zaman xətti), **Agentlər** (screenshot-lu canlı lövhə),
**Hesabatlar** (tarixçə, xərc, stabillik). GET olmayan sorğular hər başlanğıcda yaranan `X-Petek-Token` tələb edir;
yad `Host`/`Origin` başlıqları rədd edilir.

## Hesabatlar, CI və AI-nız üçün tapıntılar

Hər run `evidence/<run>/report/`-a yazır: `index.html` və `report.md` (detal qatı: hər addım, sübutu, gecikmə, xərc),
`summary.html` (müştəri qatı: qısa cümlələrlə bir səhifə, üç rəf — *saytda düzəldilməli*, *Pətək bacarmadı*, *bir insan
baxmalıdır*), `share.html` (screenshot-lar içində tək fayl; AI provayderi, model və sübut səviyyələri ilə, göndərmək
üçün), `junit.xml` (addımlar test kimi) və `findings.sarif` (tapıntılar code scanning üçün). `petek run --ci` JUnit və
SARIF yollarını çap edir, Markdown hesabatı GitHub job summary-yə əlavə edir; şablonlar: `docs/ci/github-actions.yml`,
`docs/ci/gitlab-ci.yml`. `petek findings <run|latest> --json` (və MCP `get_finding_bundle`) kodlaşdıran AI-nıza hər
tapıntını addımı, sorğu və cavabı, oracle cavabı və screenshot yolu ilə verir ki, səbəbi kodunuzda axtarsın.

## MCP serveri və `--json`

Eyni use-case-lərin ev sahibi AI üçün iki üzü daha var (ADR-0009, R10). `petek mcp` stdio üzərindən Model Context
Protocol serveridir (əl ilə yazılmış JSON-RPC, əlavə kitabxana yoxdur; `initialize`, `ping`, `tools/list`,
`tools/call`); `petek init` onu layihənin MCP faylında `petek` serveri kimi qeyd edir. Alətlər: `list_targets`,
`get_capacity`, `explore_site` (`wait` ilə), `get_exploration`, `cancel_exploration`, `list_unknowns`, `answer_unknown`,
`compare_explorations`, `generate_scenario`, `list_scenarios`, `get_scenario`, `diff_scenarios`, `get_run_plan`,
`approve_scenario`, `freeze_scenario`, `run_campaign` (`wait` ilə), `cancel_run`, `list_runs`, `get_run_status`,
`get_findings` (A/B/C mənbələri və sübut id-ləri), `get_evidence` (screenshot və ya capture-un tam yolu), `get_triage`,
`run_triage`, `get_stability`, `teardown`. Sessiya `petek mcp --allow-writes` ilə başlamayıbsa yalnız oxudur: run,
təsdiq, teardown və yazma ilə kəşfiyyat rədd edilir; hədəf siyasəti hər yerdəki kimi tətbiq olunur. Hər nəticə panelin
JSON-unu mətn və strukturlu məzmun kimi daşıyır; uğursuzluq panelin mesajı ilə `isError` nəticəsidir. `.env` yoxdursa
server panel kimi lokal fake target-dan istifadə edir.

`petek --json <əmr>` `doctor`, `init`, `plan`, `run`, `report` və `teardown` üçün stdout-a bir JSON sənəd çap edir
(loglar stderr-də qalır; uğursuzluq adi çıxış kodu ilə `{"error": ...}`), skriptlər və CI üçün.

## Arxitektura

Gradle modullarında feature-əsaslı clean architecture: hər imkan `features/<ad>`-dır, içində `domain` (saf Kotlin:
model, portlar) → `application` (use-case-lər) → `infrastructure` (Playwright, Ktor, Exposed, SDK-lar). Asılılıqlar
port vasitəsilə, konstruktor injection ilə; `app/` yeganə composition root-dur; DI framework yoxdur. `e2e/`-dəki Konsist
testləri qat qaydası pozulanda build-i dayandırır.

| Modul | Məsuliyyət |
|---|---|
| `core/domain`, `core/sqlite` | ID-lər, harness saatı, `Secret`, `TargetPolicy`; sübut qovluğu başına bir SQLite bazası |
| `features/campaign` | Kampaniya modeli, aktor qrammatikası, şablonlar, validasiya, hədəf profili və axınlar |
| `features/identity` | Deterministik kimlik reyestri |
| `features/browser` | Təcrid olunmuş Playwright sessiyaları, snapshot-lar, real-time nəqliyyat aşkarı, yarış sübutu |
| `features/llm` | `LlmClient` portu, agent CLI profilləri (`.env`-dən istənilən alət, Codex, Gemini, OpenCode), Anthropic və OpenAI-uyğun API adapterləri, retry/limit/ölçmə/ehtiyat dekoratorları |
| `features/agent` | Hərəkət whitelist-i, qərar protokolu, agent dövrəsi, hədəf axınları üzrə `run` funksiyaları |
| `features/mail`, `features/oracle` | Poçt mənbələri və hədəfin test API-si |
| `features/verification` | Tipli assertlər və yarış hökmləri |
| `features/orchestration` | Run həyat dövrü, hadisə şini, planlayıcı, watchdog, teardown, canlı lövhə |
| `features/evidence`, `features/reporting` | Sübut bazası; üç mənbəli hakim, stabillik, hesabatlar |
| `features/explorer`, `features/scenarios` | Sayt modeli və layihələr; versiyalı ssenarilər və triaj |
| `features/dashboard`, `features/capacity` | Veb panel; tutum tövsiyəsi |
| `app`, `testing/fake-target`, `e2e` | CLI və montaj; kontrakt saytı; arxitektura və e2e testləri |

Detallar, diaqramlar və modul cədvəli: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Qərarlar: [docs/adr](docs/adr).
Tələb-tələb arxitektura: [docs/requirements](docs/requirements).

## Təhlükəsizlik

- Yazmaq üçün saytın sahibliyi təsdiqlənməlidir (`petek verify`: `/.well-known/petek-verification.txt` faylı və ya
  `_petek-verification.<host>` DNS TXT qeydi; `localhost` və özəl şəbəkə təsdiqsiz keçir). Təsdiqsiz sayt yalnız
  oxunur, `petek run` exit 2 ilə imtina edir. Yalnız sahibi olduğunuz pre/stage saytı, yalnız test hesabları; real
  istifadəçi hesabı heç vaxt.
- Sirlər (`PETEK_TEST_TOKEN`, API açarları, test parolları) `Secret` dəyərləridir: heç vaxt loglanmır, AI-a
  göndərilmir; agent `{self.password}` yazır, harness əvəz edir.
- Production hostlar açıq icazə olmadan rədd edilir; oracle yazıları və teardown yalnız `is_test` şirkətlərinə toxunur.
- AI alətsiz, MCP serversiz, settings-siz və sessiya yaddaşı olmadan işləyir; proseslər shell-siz başladılır; mühit
  təmizlənir.
- Panel yalnız loopback-ə bağlanır, yazılar üçün hər başlanğıcda yaranan token istəyir, yad origin-ləri rədd edir.
- Dialoq mətnləri və səhifə mətni sübuta və AI-a çatmazdan əvvəl sirlərdən təmizlənir.

Təhlükə modeli, nəzarətlər və zəiflik bildirmə: [SECURITY.md](SECURITY.md).

## Yol xəritəsi

Faza 0–7 (MVP, kəşfiyyatçı, triaj, veb panel) icra olunub. [docs/PLAN.md](docs/PLAN.md)-dəki "Pətək 2" planı:

| Faza | Məqsəd |
|---|---|
| 8 | Bünövrə düzəlişləri; lisenziya, `workspace_id`, edition portları, opt-in telemetriya |
| 9 | Provayder-agnostik AI qatı: `PETEK_LLM_PROVIDER=auto`, generic CLI agentləri, OpenAI-uyğun HTTP |
| 10 | Hədəf profilləri, giriş zənciri (test şirkəti → öz hesablar → özü qeydiyyat → anonim), IMAP və manual OTP, sübut səviyyələri |
| 11 | Alət üzü: MCP server və `--json` CLI — istənilən ev sahibi AI Pətəki idarə edə bilsin |
| 12 | Skill paketi (`petek init`), paylanma (Docker, CLI, `petek dev`), CI rejimi, paylaşıla bilən hesabatlar |
| 13 | Universal hədəf modeli: şirkət anlayışı isteğe bağlı, sərbəst rollar, kor test naxışları |
| 14 | Ekosistem: stack başına kontrakt kitləri, log körpüsü, regressiya baseline-ları, hosted sürü (ödənişli) |

## Sənədlər

| Sənəd | Nə var |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | Plan: məqsəd, əhatə, dizayn qərarları, ssenari formatı, Faza 0–14, uğur meyarları |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modullar, asılılıq qaydaları, run həyat dövrü, agent dövrəsi, hədəf axınları, kəşfiyyatçı, panel, təhlükəsizlik |
| [docs/requirements](docs/requirements) | Hər tələb üçün bir arxitektura sənədi: modullara, testlərə və ADR-lərə izlənə bilirlik |
| [docs/adr](docs/adr) | Arxitektura qərar qeydləri 0001–0011 |
| [docs/TARGET_CONTRACT.md](docs/TARGET_CONTRACT.md) | Hədəfin test rejimində nə verməli olduğu |
| [SECURITY.md](SECURITY.md) · [CONTRIBUTING.md](CONTRIBUTING.md) · [AGENTS.md](AGENTS.md) | Təhlükəsizlik siyasəti; töhfə qaydaları; bu repoda AI kod agentlərinin izlədiyi qaydalar |

## İnkişaf

```bash
./gradlew build                 # kompilyasiya, unit testlər, ktlint, lisenziya başlıqları, arxitektura testləri, örtük
./gradlew spotlessApply         # formatlama və yeni fayllara lisenziya başlığı
./gradlew e2eTest               # fake target + real Chromium: panel, e2e modulu, 30 sessiyalı izolyasiya sübutu
./gradlew :e2e:liveTest         # real AI provayderi (planınızı və ya açarınızı işlədir)
```

Kod bazasını sağlam saxlayan qaydalar (mümkün olan yerdə build məcbur edir): Kotlin warning-lər xətadır; hər mənbə
faylı lisenziya başlığı daşıyır; domain kodu framework import etmir; application kodu infrastructure import etmir;
infrastructure-ı yalnız `app` bağlayır; mock kitabxanası yoxdur (fake-lər `testFixtures`-dadır); testlər cümlə kimi
adlanır; yeni kitabxana sahibin təsdiqini istəyir; hər commit-dən əvvəl `./gradlew spotlessApply build` keçməlidir. Tam
siyahı: [CONTRIBUTING.md](CONTRIBUTING.md). Branch-lar: `main` buraxılış branch-ıdır (Release workflow-u üstündə əl
ilə, versiya ilə başladılır və bundle-ları, image-i, npm başladıcısını dərc edir); `develop` inteqrasiya branch-ıdır;
`petek-mvp` və `petek-mvp-o6tpsw` MVP tarixçəsi kimi saxlanır. CI heç bir push-da işləmir; qapı lokal `./gradlew build`-dir.

## Lisenziya, ticarət nişanı və müəllif hüququ

Müəllif hüququ © 2026 **Kodcraft**. Müəllif: **Aslan Aslanov**.

Pətək **Apache License, Version 2.0** ([LICENSE](LICENSE)) ilə açıq mənbədir: onu istifadə edə, kopyalaya, dəyişə və
yaya bilərsiniz, kommersiya məqsədi ilə də, bir şərtlə ki lisenziyanı və qeydləri ([NOTICE](NOTICE)) saxlayasınız. Hər
mənbə faylı Spotless-in məcbur etdiyi başlığı daşıyır. "Pətək" Kodcraft-ın ticarət nişanıdır, lisenziya onun adına və
loqosuna hüquq vermir (6-cı bölmə). Töhfələr xoş gəlir: [CONTRIBUTING.md](CONTRIBUTING.md).
