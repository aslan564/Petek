# Contributing to Pətək

Pətək is © 2026 Kodcraft (author: Aslan Aslanov) and licensed under the Business Source License 1.1. Contributions are
welcome under the terms below; the same rules apply to people and to AI coding agents (see `CLAUDE.md`).

## Terms

- By submitting a contribution you confirm that you wrote it (or have the right to submit it) and you license it to
  Kodcraft under the project licence, including the right to relicense it under the Change License (Apache 2.0) on the
  Change Date and to offer it under commercial terms. This keeps the project relicensable as one work.
- Every source file carries the project header. `./gradlew spotlessApply` adds it; the build fails without it. Do not
  add other copyright lines; authorship is recorded in git.

## Workflow

1. Branch from `develop` (`feature/<topic>` or `fix/<topic>`). `petek-mvp` and `petek-mvp-o6tpsw` are frozen history.
2. Read the relevant part of `docs/PLAN.md` (the phase you touch), `docs/ARCHITECTURE.md` and the requirement
   document in `docs/requirements/` before writing code. If your change alters architecture, write or amend an ADR in
   `docs/adr/` first and wait for the owner's approval.
3. Small, logical commits; each commit must pass `./gradlew spotlessApply build` (compile with warnings as errors,
   unit tests, ktlint, licence headers, Konsist architecture tests, coverage). Run `./gradlew :e2e:e2eTest` when you
   touch the browser, the agent loop, flows or the panel.
4. Open a pull request against `develop` using the template. CI must be green. The owner reviews and merges.

## Rules the build enforces

- Kotlin 2.4, JDK 25 toolchain, `allWarningsAsErrors`, progressive mode.
- ktlint (official style, 140 columns) through Spotless; the licence header on every `.kt`, `.kts`, `.js`, `.css`,
  `.html` file.
- Konsist (`e2e/src/test/kotlin/az/petek/architecture/ArchitectureTest.kt`): `domain` imports no framework and no
  `application`/`infrastructure`; `application` never imports `infrastructure`; `infrastructure` imports only its own
  feature's `infrastructure`; nothing outside `app` imports `az.petek.app`; every layer sits under its feature root.
- Kover coverage verification per module.

## Rules reviewers enforce

- Feature-based clean architecture: `features/<name>/{domain,application,infrastructure}`; ports in `domain`,
  constructor injection, no DI framework, wiring only in `app/`. Infrastructure classes are `internal` where possible.
- The eleven "never break" rules of `CLAUDE.md`: the harness measures time; code evaluates assertions; agents act only
  through the `AgentAction` whitelist; everything has an id; no verdict without evidence; deterministic work is a
  `run` step; identities are created only by the orchestrator; oracle/teardown only on `is_test`; one Playwright per
  session on its own dispatcher; secrets travel as `Secret`; ask before adding a library.
- Tests: JUnit 6 + Kotest assertions, no mocking library — fakes live in `testFixtures`. Test names are sentences in
  backticks. Real browser → `@Tag("e2e")`, real AI → `@Tag("live")`.
- Code, identifiers, KDoc and commit messages are English. Panel texts and owner-facing explanations are Azerbaijani.
- Docs move with the code: `scenarios/*.yaml`, `docs/ARCHITECTURE.md`, the requirement document and `README.md`
  whenever the schema, a module boundary or a user-visible behaviour changes.
- Never commit `.env`, evidence, reports, storage states or databases (see `.gitignore`).

## Commit messages

Imperative subject line (≤ 72 chars) that says what the change does for the reader of `git log`; a body that explains
why and what was verified. Reference requirement ids (`R09`) and ADRs where relevant. No model or tool names in
commits, titles or code.

## Security

Never open a public issue for a vulnerability; follow `SECURITY.md`.
