# ADR-0013: Open source under Apache 2.0 now; DCO sign-off; maintainers merge into develop, the owner into main

**Status:** Accepted
**Date:** 2026-09-26
**Deciders:** Aslan (owner)
**Supersedes:** the licence part of ADR-0011

## Context
Pətək was licensed under the Business Source License 1.1 with Apache 2.0 as its Change License on 2030-09-25
(ADR-0011). The repository is public. The owner wants the project to be fully open source so that people can add to
it, while keeping control over what is merged and released.

## Decision
- **Licence:** Apache License, Version 2.0, from now on (the Change License of the BSL, brought forward). `LICENSE` holds
  the standard text; `NOTICE` names Kodcraft, the author and the trademark; every source file carries the Apache header
  from `build-logic/src/main/kotlin/PetekLicense.kt`, enforced by Spotless.
- **Contributions:** licensed under Apache 2.0 like the rest of the project (section 5 of the License). Every commit
  carries a Developer Certificate of Origin sign-off (`git commit -s`); no separate contributor agreement.
- **Trademark:** "Pətək" and its logo stay Kodcraft's; the licence grants no right to them (section 6).
- **Who merges:** `develop` is the default branch. Anyone may open a pull request; only collaborators the owner adds can
  merge into `develop` (pull request required, conversations resolved, no force push, no deletion). Only the owner
  updates `main` (a repository ruleset lets nobody but the admin update it). The owner keeps pushing directly.

## Consequences
+ Anyone can use, change and redistribute Pətək, commercially too; contributors know their terms up front.
+ The owner still decides what lands in `develop` (through the maintainers he picks) and alone decides releases.
- Paid editions (ADR-0011) remain possible as separate modules, but the open core itself can be used commercially by
  anyone; the edition boundary is a product choice, no longer a licence restriction.
- Earlier releases (0.1.0, 0.1.1) were published under BSL 1.1; later ones are Apache 2.0.
