# R13 — Architecture and code quality enforced by the build, not by discipline

**Status:** Implemented (Faza 8) · **ADRs:** 0001 · **Rules:** `CLAUDE.md`, `CONTRIBUTING.md`

## Requirement

The owner's words: "kod keyfiyyəti, arxitektura qorunmalıdır". Clean architecture, SOLID and the project's rules must
survive many contributors and many AI coding agents working in parallel: a violation must fail the build, not wait
for a reviewer to notice.

## Architecture

- **Module boundaries by the compiler.** Every feature is a Gradle module; a feature can only use what its
  `build.gradle.kts` declares (ADR-0001).
- **Layer rules by Konsist** (`e2e/src/test/kotlin/az/petek/architecture/ArchitectureTest.kt`, runs with `build`):
  layer architecture Domain ← Application ← Infrastructure; `domain` imports no framework (`io.ktor`,
  `com.microsoft.playwright`, `org.jetbrains.exposed`, `com.anthropic`, `com.github.ajalt`, `java.sql`, `org.slf4j`,
  `ch.qos.logback`) and no `application`/`infrastructure`; `application` never imports `infrastructure`;
  `infrastructure` imports only its own feature's `infrastructure`; nothing outside `app` imports `az.petek.app`;
  every layer package sits under its feature root. The e2e test task runs from the repository root so Konsist sees
  every module's production sources.
- **Compiler and style.** `allWarningsAsErrors`, progressive mode, `-Xjsr305=strict`; ktlint (official style, 140
  columns) via Spotless on Kotlin and Gradle scripts; the licence header on every source file (R14).
- **Coverage.** Kover verification per module and aggregated at the root.
- **Tests without mocks.** Fakes in `testFixtures` (`FakeBrowserSession`, `ScriptedLlmClient`, `InMemoryEvidence`,
  `FakeMailbox`, `FakeTargetOracle`, `FakeHarnessClock`) keep tests honest about ports.
- **CI.** `.github/workflows/build.yml` runs `./gradlew build` (with the Chromium the browser tests need) on every push
  to `develop`/`petek-mvp` and on pull requests into `develop`; documentation-only changes skip it. The end-to-end job
  (`./gradlew e2eTest`: panel end to end, e2e module, 30 real Chromium contexts; then 5 000 testers through the
  orchestrator) runs only on a push to
  `develop` or by hand (`workflow_dispatch`), so runner minutes are spent once per integration commit, never twice for
  the same commit as push and pull request. A newer push cancels the run in progress; `develop` writes the Gradle
  dependency cache the other runs read. `CODEOWNERS` routes every change to the owner; the PR template asks for the
  requirement, the architecture check and the docs.
- **Rules for agents.** `CLAUDE.md` is the single page every AI coding agent reads first; it points to the plan, the
  architecture, the contract and the eleven never-break rules.

## Verification

- The architecture test itself (7 rules) — green on `develop` as of 2026-09-25.
- `./gradlew build` is the gate for every commit; CI repeats it.

## Open items

- A Konsist rule that keeps HR concepts out of `core/domain` (R11, Faza 13) and one that keeps paid-edition modules
  out of the open core (R14, ADR-0011).
- Kover thresholds are per module and modest; raise them with Faza 9–10 test additions.
