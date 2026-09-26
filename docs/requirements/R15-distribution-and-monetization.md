# R15 — Distribution and monetization: installable in any project, runs beside it and in CI; paid editions possible

**Status:** Distribution done 2026-09-26 (the Release workflow, started by hand on `main`, publishes platform bundles with a jlink runtime and the
platform's Playwright driver, `petek-<v>-<platform>.tar.gz|zip`, the generic `petek-<v>-any-jdk25.zip`, `SHA256SUMS`,
the Docker image `ghcr.io/aslan564/petek:<v>` for linux/amd64 and linux/arm64, and the npm launcher `petek` when
`NPM_TOKEN` is set; `petek init` prepares a project; v0.1.0 is published), the rest planned (CI mode, `petek dev`,
Faza 14) · **ADRs:** 0009, 0011

## Requirement

The owner wants to publish Pətək so that any project can add it (the owner first mentioned Maven; the agreed shape is
a sidecar), start it alongside the application, open the panel, point it at the site, explore, run and get reports —
and, when the time comes, earn from it.

## Why

Adoption needs a one-command install and a one-command start; income needs scale, history, team features and hosting
that the open core deliberately leaves to paid editions, without ever carrying the model cost (R09).

## Architecture

- **Sidecar, not a library in the target's build.** Pətək ships as a standalone distribution: platform bundles
  (`app/build.gradle.kts` `bundle`: `bin/petek` or `bin/petek.cmd`, `lib/` with Playwright's driver-bundle jar repacked
  for the one platform, `runtime/` from jlink with the modules jdeps finds plus locale, charset, EC and zipfs data, the
  documents; `-Ppetek.platform=` names the platform, the runtime always comes from the building JDK, so the release
  matrix builds each bundle on its own runner), the generic `any-jdk25` zip from the distribution plugin, the Docker
  image (`docker/Dockerfile`: the Linux bundle on Playwright's official image of the same Playwright version, so
  Chromium and its libraries are inside and nothing is downloaded at run time; `docker/prepare-context.sh` lays a
  bundle out per architecture and buildx builds linux/amd64 and linux/arm64 in one go; the project is mounted as
  `/work`; the panel keeps binding loopback, so it needs `--network host`, and the image's first use is CI with
  `--json`) and the `npx petek` launcher that only downloads, verifies and starts a bundle. Embedding Playwright, Chromium, SQLite and an AI CLI into a target's Maven/npm build would be heavy and
  fragile; the sidecar keeps the target untouched. The launcher passes `-Dpetek.home` (the bundle's directory) for
  the templates `petek init` will ship.
- **In the project:** `petek init` writes `.petek/` and `petek.yaml` (target profile) plus the skill pack (R10);
  `petek dev` starts the panel when the app's health URL answers.
- **CI mode:** `petek run --ci` → exit code, JUnit XML, SARIF for findings, HTML report artifact; GitHub Actions and
  GitLab templates; frozen `run` scenarios execute without an AI.
- **Contract kits** (Faza 14): Spring Boot starter, Express router, Laravel package that implement the `/test/...`
  contract of `docs/TARGET_CONTRACT.md` in one line — the only Pətək piece that lives inside the target.
- **Business model (ADR-0011):** open core + paid modules/hosted: hosted swarm (multi-machine orchestration), report
  history and trends, shared panel, SSO/audit, report hosting — behind the ports named in R14. Opt-in, counters-only
  telemetry (`UsageSink`) so pricing can be grounded. Shareable single-file reports as sales material.

## Modules touched

`app` (`init`, `dev`, `--ci`, `--json`), `build-logic` (distribution, publishing conventions), `reporting`
(shareable report), `orchestration`/`evidence` (ports for paid implementations), new `kits/` repositories.

## Verification

- Done: the linux-x64 bundle, extracted outside the repository and started with no JDK on `PATH` (through a symlink,
  from a site directory), runs `--help` and `doctor` against the real target (Chromium starts from the repacked
  driver); `build.yml` (run by hand) repeats the `--help` start, `release.yml` on every platform before
  publishing.
- Done: `build.yml` (run by hand) builds the image from the Linux bundle and checks with `--json doctor`
  that Chromium starts inside it; `release.yml` pushes the multi-architecture image to GHCR.
- Planned: the Docker image runs the contract demo end to end; `petek init` on empty Node and Spring projects; the
  GitHub Action template (`docs/ci/github-actions.yml`) on the fake target turns red on an injected failure.

## Open items

- Owner: register names (GitHub org, domain, npm, Maven); decide whether paid modules live in a separate repository
  (recommended) or a `premium/` module.
- Do not build the hosted service before three to five paying customers use the local tool.
