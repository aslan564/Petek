# R17 — Mobile apps: Android and iOS testers beside web testers

**Status:** Planned · **Plan:** Faza 26.0–32 ("Pətək 4" in `docs/PLAN.md`) · **ADRs:** 0015, 0003, 0006, 0007, 0008

## Requirement

The same Pətək run can put testers on a site's Android and iOS apps as well as on its web pages. A device tester
reads the screen from the platform's accessibility tree (and, where the AI can take it, a screenshot), acts only
through whitelisted actions, and is measured by the harness clock and judged by code like a web tester. One scenario
can coordinate actors across platforms (an announcement published on the web, seen in the app) and report the
delivery time with its precision. Each device tester can carry a persona: device model, OS level, language, font
scale, theme and location.

## Why

Many targets are one backend with a web client and two apps. The multi-user and real-time bugs Pətək looks for cross
those clients, and single-user problems that only show on a phone (cut labels at large font sizes, untranslated text,
crashes, unnamed controls) are not seen in a browser. The owner approved the plan on 2026-11-27.

## Architecture (by phase)

- **Faza 26.0 — non-ASCII typing experiment** (one day, first): a test keyboard or the clipboard on an Android 14
  emulator, locally and in CI; the owner picks the way. The mobile version does not ship without it.
- **Faza 26 — the shared session port:** `UiSession` split out of `BrowserSession`; the agent depends on it. No
  behaviour change.
- **Faza 27 — Android:** `features/device`, `adb`-driven `DeviceSession`, emulator pool, persona settings, video,
  crash and accessibility findings, typing of non-ASCII text.
- **Faza 28 — scenarios:** `platform` and `device` per actor, app builds and backend host in the target profile,
  validator rules, a cross-platform contract demo.
- **Faza 29 — personas and screenshots:** persona library for every platform, image parts in `LlmMessage`.
- **Faza 30 — iOS:** `simctl` and AXe-driven `DeviceSession` (macOS only).
- **Faza 31 — CI and the live board:** workflow examples for emulators and simulators, device tiles on the panel.
- **Faza 32 — moving existing mobile test setups over:** a guide from brief-and-persona setups to scenarios.

## Safety

- A device starts only after its backend host passes `TargetPolicy` and the ownership gate (R06).
- A device never writes to a production backend; with `PETEK_ALLOW_PRODUCTION=true` it only visits, read-only.
- Test accounts reach the device through the harness (`{self.password}`), never through the AI.
- Screenshots sent to the AI show whatever the screen shows: `doctor` and the documentation warn before a device run
  and recommend a backend that holds only test data.
- Without a test API, device verdicts rest on the screen alone and the report marks them as weak evidence.

## Verification

None yet (Planned). Each phase adds its tests here; Faza 26 must keep every existing test green unchanged.

## Open items

See `docs/PLAN.md`, "Qərar gözləyən suallar (Pətək 4)".
