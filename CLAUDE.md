# Pətək — CLAUDE.md

## Layihə
Pətək çoxistifadəçili AI test platformasıdır: N AI tester agenti hədəf saytda (ilk hədəf KadroHR) eyni anda ayrı
brauzer sessiyalarında işləyir, orkestrator onları koordinasiya edir, nəticə sübut əsaslı hesabatdır.
Tam plan: `docs/PLAN.md`. Arxitektura və modul xəritəsi: `docs/ARCHITECTURE.md`. Hər tələbin arxitektura sənədi:
`docs/requirements/` (R01–R15, dəyişiklik toxunduğu tələbi yeniləyir). Hədəf saytın test kontraktı
(`data-testid`, `/test/...` endpointləri): `docs/TARGET_CONTRACT.md`. Qərarlar: `docs/adr/`. İstifadəçi sənədi:
`README.md` (EN) və `README.az.md` (AZ). Töhfə və təhlükəsizlik qaydaları: `CONTRIBUTING.md`, `SECURITY.md`.
Tapşırığa başlamazdan əvvəl uyğun bölməni oxu.

## Sahib, lisenziya və branch-lar
Müəllif hüququ © 2026 Kodcraft, müəllif Aslan Aslanov; lisenziya Business Source License 1.1 (`LICENSE`, `NOTICE`;
2030-09-25-də Apache 2.0). Hər mənbə faylı (`.kt`, `.kts`, `.js`, `.css`, `.html`) `build-logic/.../PetekLicense.kt`-dəki
başlığı daşıyır — `spotlessApply` qoyur, `spotlessCheck` (build-in içində) yoxlayır; başqa copyright sətri əlavə etmə.
`main` buraxılış branch-ıdır (yalnız `develop`-dan gəlir); `develop` inteqrasiya branch-ıdır; `petek-mvp` və
`petek-mvp-o6tpsw` MVP tarixçəsidir, dəyişdirilmir. **GitHub Actions heç bir push-da işləmir** (sahibin qərarı:
dəqiqə limiti, hər şey bitməmiş deploy yoxdur): `build.yml` və `release.yml` yalnız əl ilə (`workflow_dispatch`)
başlayır; hər commit-in qapısı lokal `./gradlew spotlessApply build`-dir. Buraxılış: `gradle.properties` və
`launcher/package.json`-da `version`-ı qaldır, `develop`-u `main`-ə fast-forward et, Actions → Release → Run
workflow (branch `main`, versiya) — GitHub Release-ə `petek-X.Y.Z-<platform>.tar.gz|zip` (`:app:bundle`, jlink
runtime, JDK lazım deyil), `petek-X.Y.Z-any-jdk25.zip`, `SHA256SUMS`, GHCR image və (`NPM_TOKEN` varsa) npm
paketi gedir; mövcud teq rədd edilir. Commit, PR və kodda model/alət adı yazılmır.

## Stack (versiyalar `gradle/libs.versions.toml`-da)
Kotlin 2.4 / JDK 25 toolchain, Gradle 9.8 (Kotlin DSL, version catalog, `build-logic` convention plugin-ləri,
configuration cache), kotlinx.coroutines, Playwright Java, Ktor 3 (client; fake target üçün server),
kotlinx.serialization + kaml, Clikt + Mordant, SQLite (sqlite-jdbc + Exposed 1.x), kotlinx.html,
kotlin-logging + logback, Anthropic Java SDK + Claude Code CLI (LLM), JUnit 6 + Kotest assertions, Konsist,
Spotless/ktlint, Kover. Paket kökü: `az.petek`.

## Arxitektura qaydaları (`e2e/src/test/kotlin/az/petek/architecture/ArchitectureTest.kt` Konsist ilə hər build-də yoxlayır)
- Feature-based clean architecture: hər feature `features/<ad>/` modulu, içində üç qat:
  `az.petek.<ad>.domain` → `application` → `infrastructure`.
- `domain`: saf Kotlin (model, port interfeysləri, saf qaydalar). Framework importu yoxdur
  (`io.ktor`, `com.microsoft.playwright`, `org.jetbrains.exposed`, `com.anthropic`, `com.github.ajalt`, `java.sql`).
  `kotlinx.coroutines` və `kotlinx.serialization.json` icazəlidir.
- `application`: use-case-lər; yalnız domain-lərə (öz və digər feature-lərin) və digər feature-lərin
  `application`-ına bağlıdır. Heç vaxt `infrastructure`-a bağlı deyil.
- `infrastructure`: portların implementasiyası (Playwright, Ktor, Exposed, SDK). Yalnız öz feature-inin
  `infrastructure`-ını import edə bilər; başqa feature-in `infrastructure`-ı yalnız `app` (composition root) tərəfindən görülür.
- Asılılıqlar interfeys (port) vasitəsilə, konstruktor injection ilə; DI framework yoxdur, montaj `app/`-dadır.
- SOLID: kiçik fokuslu interfeyslər (ISP), dekoratorlar (retry, limit, metering) ilə genişlənmə (OCP).
- `infrastructure` sinifləri mümkün qədər `internal`; xaricə yalnız port və factory açılır.

## Pozulmamalı qaydalar
1. Vaxtı həmişə harness ölçür (`HarnessClock`: `Instant` + `System.nanoTime`), LLM yox.
2. Assertləri həmişə kod yoxlayır, LLM yox.
3. Agent yalnız `AgentAction` whitelist-indəki əməliyyatları edə bilər; yeni əməliyyat = kod dəyişikliyi, prompt yox.
4. Hər şeyin ID-si var: `run_id`, `agent_id`, `step_id`, `event_id`, `correlation_id`.
5. Sübutsuz nəticə yoxdur: hər addım `step` cədvəlinə, hər keçdi/keçmədi ən azı bir screenshot və ya oracle cavabına bağlanır.
6. Deterministik olan `run`, yalnız düşüncə tələb edən `do`.
7. Kimliyi yalnız orkestrator yaradır (identity feature); agent öz kimliyini yalnız oxuyur.
8. Oracle və teardown yalnız `is_test=true` şirkətlərdə işləyir; hədəf `.env`-dəki `PETEK_TARGET`-dir;
   `PETEK_PRODUCTION_HOSTS`-dakı host yalnız `PETEK_ALLOW_PRODUCTION=true` ilə qəbul olunur.
9. Playwright Java thread-safe deyil: hər `BrowserSession`-un bütün Playwright çağırışları onun öz tək-thread
   dispetçerində icra olunur; hər sessiya öz `Playwright` instansını yaradır. Bir Playwright obyektini iki thread-dən çağırma.
10. Sirlər (`PETEK_TEST_TOKEN`, API açarları, test parolları) `Secret` ilə gəzir, loga və LLM-ə düşmür;
    agent parolu `{self.password}` placeholder-i ilə yazır, harness əvəz edir.
11. Yeni kitabxana əlavə etməzdən əvvəl soruş (version catalog-dakılar təsdiqlənib).
12. Saxta ekran, saxta səhifə, uydurma nəticə qəti qadağandır: yalnız verilən sayt (`PETEK_TARGET`) test olunur.
    Sayt cavab vermirsə və ya bloklanıbsa run və kəşfiyyat başlamır, səbəb olduğu kimi bildirilir
    (`TargetReachability`); sayt verilməyibsə panel, MCP və CLI sahibdən soruşur və cavab gələnə qədər heç nə etmir.
    Fake target (`testing/fake-target`) yalnız Pətəkin öz e2e testləri üçündür (`--env-file .env.fake-target` ilə
    açıq şəkildə) və heç vaxt sahibin nəticəsi kimi təqdim edilmir.

## Əmrlər
    docker compose up -d                                   # Mailpit :1025 / :8025
    ./gradlew build                                        # compile + unit testlər + ktlint + lisenziya başlıqları + arxitektura testləri
    ./gradlew spotlessApply                                # formatlama
    ./gradlew e2eTest                                      # fake target + real Chromium ilə e2e (panel, e2e modulu, 30 sessiyalı izolyasiya sübutu)
    ./gradlew :e2e:liveTest                                # real LLM ilə (Claude planından istifadə edir)
    ./gradlew :testing:fake-target:run                     # lokal fake KadroHR: http://127.0.0.1:18080, poçt 18025
    ./gradlew :app:run --args="--env-file .env.fake-target doctor"   # fake saytla yoxlama (IntelliJ: hazır run konfiqurasiyaları)
    ./gradlew :app:run --args="doctor"
    ./gradlew :app:run --args="init --dir /path/to/site --target https://staging.site"   # müştəri layihəsini hazırlayır (.env, .petek/, skill paketi, MCP qeydi)
    ./gradlew :app:bundle                                  # bu platformun bundle-ı (jlink runtime, JDK-sız): app/build/distributions/
    docker/prepare-context.sh app/build/distributions/petek-<v>-linux-x64.tar.gz amd64 && docker build -f docker/Dockerfile -t petek docker/   # Docker image (CI də edir)
    node --test launcher/test/*.test.js                    # npx petek başladıcısının testləri (Node 18+)
    ./gradlew :app:run --args="capacity"                   # bu maşın üçün tövsiyə olunan maksimum tester (limit deyil)
    ./gradlew :app:run --args="capacity --measure 5"       # real sessiyalarla ölçərək
    ./gradlew :app:run --args="plan scenarios/kadrohr.yaml"
    ./gradlew :app:run --args="run scenarios/kadrohr.yaml --repeat 3"
    ./gradlew :app:run --args="report <run_id>"
    ./gradlew :app:run --args="teardown --run <run_id>"

## Kod konvensiyaları
- `suspend` funksiyalar; bloklayan I/O yalnız `Dispatchers.IO`-da və ya sessiyanın öz dispetçerində.
- Konfiqurasiya və ssenari modelləri domain `data class`-larıdır; YAML DTO-ları infrastructure-dadır.
  Sxem dəyişəndə `scenarios/kadrohr.yaml` (real KadroHR), `scenarios/contract-demo.yaml` (fake target, `docs/PLAN.md`-dəki nümunə) və `docs/ARCHITECTURE.md` də yenilənir.
- Testlər: JUnit 6 + Kotest assertions (`shouldBe`), mock kitabxanası yoxdur — `testFixtures`-dakı fake-lər
  (`FakeBrowserSession`, `ScriptedLlmClient`, `InMemoryEvidence`, `FakeMailbox`, `FakeTargetOracle`, `FakeHarnessClock`).
  Test adları backtick ilə, cümlə kimi. Real brauzer tələb edən testlər `@Tag("e2e")`, real LLM `@Tag("live")`.
- Loglarda MDC: `run_id`, `agent_id`. Sirlər heç vaxt loglanmır.
- Kod, identifikatorlar, KDoc və commit mesajları ingiliscə; istifadəçiyə izahlar Azərbaycan dilində. Məhsulun AI-ı
  (kəşfiyyatçı, testerlər, triaj) sahibin dilində yazır: `PETEK_LANGUAGE` (`WorkingLanguage`, default `auto`); heç bir
  prompt dili Azərbaycan dilinə məcbur etmir.
- Hər dəyişiklikdən sonra `./gradlew spotlessApply build` keçməlidir (warnings = errors).

## İş üsulu
- Plandan kənar arxitektura dəyişikliyi lazımdırsa, əvvəl izah et və təsdiq gözlə.
- Kiçik, məntiqi commit-lər; hər commit-də `./gradlew build` keçməlidir.
- Bilinməyən bir şey (hədəfin real-time mexanizmi, qeydiyyat axını) varsa uydurma — `docs/PLAN.md`-nin
  "Qərar gözləyən suallar" bölməsinə əlavə et və soruş.
