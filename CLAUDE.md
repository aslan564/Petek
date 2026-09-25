# Pətək — CLAUDE.md

## Layihə
Pətək çoxistifadəçili AI test platformasıdır: N AI tester agenti hədəf saytda (ilk hədəf KadroHR) eyni anda ayrı
brauzer sessiyalarında işləyir, orkestrator onları koordinasiya edir, nəticə sübut əsaslı hesabatdır.
Tam plan: `docs/PLAN.md`. Arxitektura və modul xəritəsi: `docs/ARCHITECTURE.md`. Hədəf saytın test kontraktı
(`data-testid`, `/test/...` endpointləri): `docs/TARGET_CONTRACT.md`. Tapşırığa başlamazdan əvvəl uyğun bölməni oxu.

## Stack (versiyalar `gradle/libs.versions.toml`-da)
Kotlin 2.4 / JDK 25 toolchain, Gradle 9.8 (Kotlin DSL, version catalog, `build-logic` convention plugin-ləri,
configuration cache), kotlinx.coroutines, Playwright Java, Ktor 3 (client; fake target üçün server),
kotlinx.serialization + kaml, Clikt + Mordant, SQLite (sqlite-jdbc + Exposed 1.x), kotlinx.html,
kotlin-logging + logback, Anthropic Java SDK + Claude Code CLI (LLM), JUnit 6 + Kotest assertions, Konsist,
Spotless/ktlint, Kover. Paket kökü: `az.petek`.

## Arxitektura qaydaları (Konsist testləri `e2e/` modulunda bunları yoxlayır)
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

## Əmrlər
    docker compose up -d                                   # Mailpit :1025 / :8025
    ./gradlew build                                        # compile + unit testlər + ktlint + arxitektura testləri
    ./gradlew spotlessApply                                # formatlama
    ./gradlew :e2e:e2eTest                                 # fake target + real Chromium ilə e2e
    ./gradlew :e2e:liveTest                                # real LLM ilə (Claude planından istifadə edir)
    ./gradlew :testing:fake-target:run                     # lokal fake KadroHR: http://127.0.0.1:18080, poçt 18025
    ./gradlew :app:run --args="--env-file .env.fake-target doctor"   # fake saytla yoxlama (IntelliJ: hazır run konfiqurasiyaları)
    ./gradlew :app:run --args="doctor"
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
- Kod, identifikatorlar, KDoc və commit mesajları ingiliscə; istifadəçiyə izahlar Azərbaycan dilində.
- Hər dəyişiklikdən sonra `./gradlew spotlessApply build` keçməlidir (warnings = errors).

## İş üsulu
- Plandan kənar arxitektura dəyişikliyi lazımdırsa, əvvəl izah et və təsdiq gözlə.
- Kiçik, məntiqi commit-lər; hər commit-də `./gradlew build` keçməlidir.
- Bilinməyən bir şey (hədəfin real-time mexanizmi, qeydiyyat axını) varsa uydurma — `docs/PLAN.md`-nin
  "Qərar gözləyən suallar" bölməsinə əlavə et və soruş.
