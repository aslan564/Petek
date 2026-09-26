# Contributing to Pətək

Pətək is © 2026 Kodcraft (author: Aslan Aslanov) and open source under the Apache License 2.0. Contributions are
welcome under the terms below; the same rules apply to people and to AI coding agents (see `AGENTS.md`).

## Terms

- A contribution is licensed under the Apache License 2.0, like the rest of the project (section 5 of the License:
  what you submit is under the same terms).
- Every commit carries a Developer Certificate of Origin sign-off (`git commit -s` adds
  `Signed-off-by: Your Name <you@example.com>`): with it you certify that you wrote the change or have the right to
  submit it under the project licence (https://developercertificate.org). Pull requests without it are not merged.
- Every source file carries the project header. `./gradlew spotlessApply` adds it; the build fails without it. Do not
  add other copyright lines; authorship is recorded in git.

## Workflow

1. Branch from `develop` (`feature/<topic>` or `fix/<topic>`). `main` is the release branch and only receives
   `develop`. GitHub Actions never runs on a push (the owner's decision: the minute budget, and nothing is deployed
   before everything is finished): `build.yml` and `release.yml` start only by hand from the Actions tab. The gate on
   every commit is the local `./gradlew spotlessApply build`. A release is: bump `version` in `gradle.properties` and
   `launcher/package.json`, fast-forward `main` to `develop`, run the Release workflow on `main` with that version.
   `petek-mvp` and `petek-mvp-o6tpsw` are frozen history.
2. Read the relevant part of `docs/PLAN.md` (the phase you touch), `docs/ARCHITECTURE.md` and the requirement
   document in `docs/requirements/` before writing code. If your change alters architecture, write or amend an ADR in
   `docs/adr/` first and wait for the owner's approval.
3. Small, logical commits; each commit must pass `./gradlew spotlessApply build` (compile with warnings as errors,
   unit tests, ktlint, licence headers, Konsist architecture tests, coverage). Run `./gradlew e2eTest` (the panel end
   to end in real Chromium, the e2e module, the 30-session isolation proof) when you touch the browser, the agent
   loop, flows or the panel.
4. Open a pull request against `develop` (the default branch) using the template. Anyone may open one; only the
   maintainers the owner adds can merge into `develop`, and only the owner updates `main` when releasing. Both branches
   are protected: no direct pushes from others, no force pushes, no deletion, open review conversations block a merge.
   Actions run only by hand, so the local `./gradlew spotlessApply build` is the gate a maintainer re-runs before merging.

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
- The eleven "never break" rules of `AGENTS.md`: the harness measures time; code evaluates assertions; agents act only
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
