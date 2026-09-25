## Summary

<!-- What changes and why, in the reader's terms. Link the plan phase (docs/PLAN.md Faza N) if any. -->

## Requirements touched

<!-- docs/requirements/R.. documents this change implements or affects; update them in the same PR. -->

## Architecture

- [ ] No layer rule broken (Konsist passes); new ports live in `domain`, wiring only in `app/`
- [ ] No new library, or the owner approved it (CLAUDE.md rule 11) and the version catalog is updated
- [ ] ADR written or amended if a decision changed (`docs/adr/`)

## Verification

- [ ] `./gradlew spotlessApply build` passes locally (warnings as errors, ktlint, licence headers, architecture tests)
- [ ] `./gradlew :e2e:e2eTest` run if the browser, agent loop, flows or panel changed
- [ ] New behaviour has tests (fakes from `testFixtures`, no mocking library)

## Documentation

- [ ] `README.md` / `README.az.md`, `docs/ARCHITECTURE.md`, scenarios and the requirement document updated where the
      schema, a boundary or a user-visible behaviour changed

## Security

- [ ] No secret in code, logs, evidence or prompts; target policy and `is_test` guards untouched or strengthened
