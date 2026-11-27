# ADR-0015: Mobile apps as a second kind of session behind the same agent, harness and report

**Status:** Accepted (the owner reviewed the plan on 2026-11-27; remaining questions are in `docs/PLAN.md`,
"Pətək 4", "Qərar gözləyən suallar (Pətək 4)")
**Date:** 2026-11-27
**Deciders:** Aslan (owner)

## Context
Pətək tests web sites: every tester drives an isolated Chromium context through `BrowserSession`, and the agent loop,
the harness clock, the assertions, the evidence store and the report are built on top of it. The same product is
often also an Android and an iOS app on the same backend, and the multi-user bugs Pətək exists for cross platforms:
an administrator publishes on the web, an employee on a phone must see it; two managers approve the same request,
one from the app. `docs/TECH_DEBT.md` lists a mobile adapter (Maestro or Appium) as open item 6.

The owner already tests apps with a separate, MIT-licensed tool that runs one AI agent per emulator or simulator with
a persona (language, font scale, theme, location) and reads the screen through the platform's accessibility tree
(`adb`/`uiautomator` on Android, `simctl` and AXe on iOS). It finds single-user problems well (cut labels, crashes,
missing accessibility names, wrong language) but has no coordination, no harness clock and no code-checked verdicts:
the AI decides what a bug is.

Forces: rule 2 (code decides), rule 3 (whitelisted actions), rule 5 (evidence), rule 8 and R06 (never harm
production), rule 11 (no new library without approval), rule 13 (no real product names). Native apps usually pin
their TLS certificates, so the network traffic Pətək reads from the browser is not observable on a device. One
emulator needs about two CPU cores and 2–3 GB of memory; iOS simulators exist only on macOS.

## Decision
- **One agent, two kinds of session.** The part of `BrowserSession` that every screen has (numbered snapshot, click
  by ref, fill, select, read text, wait for and watch text, visibility, count, screenshot, accessibility snapshot) is
  split into a port `UiSession`. `BrowserSession` extends it with what only a browser has (navigate by URL, DOM,
  network observation, mutations, storage state, requests). The agent loop, run functions and verification depend on
  `UiSession` and ask for web capabilities explicitly. Phase 26 does only this split, with no behaviour change.
- **Devices are driven through their own command-line tools, not a new library.** A new feature module
  `features/device` holds the domain (`DevicePlatform`, `DeviceProfile`, `DeviceSession : UiSession`, `DevicePool`)
  and two infrastructures: Android over `adb` (`uiautomator dump`, `input`, `screencap`, `screenrecord`, `logcat`,
  `am`/`pm`) and iOS over `xcrun simctl` and AXe. They are called as external processes, like the AI CLIs of ADR-0008;
  `doctor` reports which are installed. Maestro and Appium are not used: both are a new runtime the owner would have
  to approve and maintain, and the accessibility tree they read is the same.
- **The snapshot is the same shape.** Elements of the accessibility tree become the numbered elements the agent
  already reads; a tap goes to the centre of the element's bounds. Elements without a name are kept with their
  bounds and reported by the accessibility check (no name, touch target under 48 dp / 44 pt).
- **New actions are code.** `Swipe(direction)`, `Back` and `Relaunch` join the `AgentAction` whitelist; a session
  declares the actions it supports and the decision protocol rejects the others before they run.
- **The AI may see the screen.** `LlmMessage` gains optional image parts; providers that cannot take images get the
  text snapshot only and `doctor` says so (extends ADR-0003 and ADR-0008). This is needed because app screens built
  without accessibility names cannot be read from the tree alone.
- **Scenarios name a platform per actor.** An actor has `platform: web | android | ios` and an optional `device`
  profile (model, OS level, locale, font scale, theme, location). The target profile names the app builds
  (`apps.android.apk`, `apps.android.package`, `apps.ios.app`, `apps.ios.bundle_id`) and the backend host the app talks
  to. The validator refuses assertions a device cannot prove (`http_status`, `only_one_succeeds.request`) unless an
  oracle is given.
- **Safety is the backend's, as on the web.** The backend host of an app goes through `TargetPolicy` and the ownership
  gate (R06, ADR-0012) before any device starts. Because a pinned app's writes cannot be intercepted, a device never
  writes to a production backend: with `PETEK_ALLOW_PRODUCTION=true` an app is only visited read-only, and the report
  states that this is enforced by the action whitelist and the mission, not by the network.
- **Time and evidence.** `emits` and `wait_for` work across platforms; on a device t1 is read by polling the
  accessibility tree and every latency carries its polling interval as its precision. Each step stores a screenshot;
  runs can record video; a crash in `logcat` or a new iOS crash report becomes a finding on the app shelf.
- **Capacity is counted per device.** `capacity` counts emulators by cores, memory and KVM, and simulators on macOS
  only. A run mixes many web actors with few device actors on one machine; a swarm over several machines stays in the
  paid edition (ADR-0011).

**Owner's decisions after review (2026-11-27):**
- Typing non-ASCII text on Android is a release condition: the mobile version does not ship without it. A one-day
  experiment (Faza 26.0) picks the way (a test keyboard or the clipboard) before any other work.
- The first version runs on emulators and simulators only; real devices come later.
- Screenshots may carry what the screen shows to the AI; there is no switch for it, `doctor` and the documentation
  warn and recommend backends that hold only test data.
- Without a test API, device runs are allowed and the report marks their verdicts as weak evidence.
- The mobile part is free, in the open core.

## Consequences
+ One scenario can cover web and both apps, with the same clock, assertions, evidence and report.
+ No new library; device tools are checked by `doctor` like the AI CLIs.
+ Personas (device profile, language, font, theme) can be applied to web actors too.
- Device latency is coarser than DOM latency (one accessibility poll, about 1–3 s) and is reported as such.
- Without network observation, device verdicts lean on the screen and on the target's test API.
- Android `input text` cannot type non-ASCII characters; Phase 27 must solve typing (open question).
- iOS is built and tested only on macOS (locally or a macOS CI runner).
- Code ported from the MIT-licensed tool keeps its copyright notice in `NOTICE`.
