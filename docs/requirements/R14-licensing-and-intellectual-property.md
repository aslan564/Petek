# R14 — Licensing and intellectual property: the work is protected and stays relicensable

**Status:** Implemented (Faza 8) · **ADRs:** 0011

## Requirement

The owner's words: "lisenziyası olmalıdır, hamısında mənim adım olmalıdır, bütün kodda, lisenziyalarda; müəllif
hüquqları qorunmalıdır". Every file names the author and licensor; the licence protects the idea and a future hosted
business while letting people use the tool; contributions cannot fragment ownership.

## Decisions (owner, 2026-09-25)

- **Licence:** Business Source License 1.1. Licensor **Kodcraft (Aslan Aslanov)**; Licensed Work **Pətək**;
  Additional Use Grant: production use to test software you own or operate, no Competing Offering; Change Date
  **2030-09-25**; Change License **Apache License, Version 2.0**.
- **Copyright line:** `Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.`
- **Trademark:** "Pətək" is Kodcraft's; the licence grants no trademark rights (`NOTICE`).

## Architecture

- `LICENSE` (BSL 1.1 with parameters), `NOTICE` (copyright, trademark, third-party notice).
- **Header on every source file, enforced.** `build-logic/src/main/kotlin/PetekLicense.kt` holds the text once;
  the convention plugins apply it through Spotless `licenseHeader` to `.kt`, `.kts` (modules, root, settings and
  build-logic), `.js`, `.css` and `.html`; `spotlessCheck` is part of `build`, so a file without the header fails the
  build and `spotlessApply` adds it. Delimiters are chosen so existing file comments are kept below the header.
- **Contributions** are licensed to Kodcraft under the project licence with the right to relicense on the Change Date
  and to offer commercial terms (`CONTRIBUTING.md`); no other copyright lines are added, authorship stays in git.
- **Edition boundary (ADR-0011):** paid capabilities live behind ports (`RunRepository`, `ReportStore`,
  `Orchestrator`/`AgentScheduler`, `UsageSink`) in a separate repository; the open core never imports them.
- Documentation (`README.md`, `README.az.md`, this file) states the licence, the trademark and the contact for
  commercial licensing.

## Verification

- `./gradlew spotlessCheck` (in `build`): 697 files carry the header as of 2026-09-25; a new file without it fails.
- CI repeats the check on every push and pull request.

## Open items

- `workspace_id` in the id system and the `UsageSink` telemetry port (Faza 8, ADR-0011).
- Register the `petek` name for the GitHub organisation, domain, npm and Maven coordinates (owner).
- Publish a release tag so the BSL "first publicly available distribution" date of each version is unambiguous.
