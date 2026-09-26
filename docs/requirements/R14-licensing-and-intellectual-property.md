# R14 — Licensing and intellectual property: open source, the author named everywhere

**Status:** Implemented (Faza 8; Apache 2.0 since 2026-09-26) · **ADRs:** 0013, 0011

## Requirement

The owner's words: "lisenziyası olmalıdır, hamısında mənim adım olmalıdır, bütün kodda, lisenziyalarda; müəllif
hüquqları qorunmalıdır" (2026-09-25), and "tamamilə open source eləyirəm ... ancaq hələlik main və develop protect
olmalıdır, yalnız mənim contributor elədiklərim develop-a birləşdirə bilər" (2026-09-26). Every file names the author
and the copyright holder; the project is open source; the owner decides what is merged and released.

## Decisions

- **Licence (2026-09-26):** Apache License, Version 2.0. It replaces the Business Source License 1.1 chosen on
  2026-09-25, whose Change License it already was (ADR-0013 supersedes the licence part of ADR-0011).
- **Copyright line:** `Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.`
- **Trademark:** "Pətək" and its logo are Kodcraft's; the licence grants no trademark rights (`NOTICE`, section 6).
- **Contributions:** under Apache 2.0 (section 5), each commit signed off under the Developer Certificate of Origin.

## Architecture

- `LICENSE` (the standard Apache 2.0 text), `NOTICE` (copyright, trademark, third-party notice); `launcher/LICENSE` and
  `launcher/package.json` (`Apache-2.0`) for the npm package; the release notes name the licence.
- **Header on every source file, enforced.** `build-logic/src/main/kotlin/PetekLicense.kt` holds the text once;
  the convention plugins apply it through Spotless `licenseHeader` to `.kt`, `.kts` (modules, root, settings and
  build-logic), `.js`, `.css` and `.html`; `spotlessCheck` is part of `build`, so a file without the header fails the
  build and `spotlessApply` adds it. The launcher scripts, the bundle launchers and the Docker files carry the same text.
- **Branches (GitHub settings):** `develop` is the default branch; a pull request is required there and only
  collaborators can merge; a repository ruleset lets only the admin update `main`; force pushes and deletions are
  blocked on both; `.github/CODEOWNERS` names the owner.
- **Edition boundary (ADR-0011):** paid capabilities may live behind ports in a separate repository; the open core
  never imports them.

## Verification

- `./gradlew spotlessCheck` (in `build`): every covered file carries the Apache header; a new file without it fails.
- `gh api repos/aslan564/Petek/branches/{main,develop}/protection` and `.../rulesets` show the branch rules.

## Open items

- Register the `petek` name for the GitHub organisation, domain, npm and Maven coordinates (owner).
