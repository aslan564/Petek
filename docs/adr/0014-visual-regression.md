# ADR-0014: Releases compared by how their pages look, pixel by pixel with the JDK

**Status:** Accepted (defaults open to the owner's review: docs/PLAN.md "Qərar gözləyən suallar (Pətək 2)")
**Date:** 2026-10-01
**Deciders:** Aslan (owner): the regression baseline first (2026-09-30), screenshot difference part of it

## Context
`petek compare` sets a run against an earlier run or release of its scenario: steps the site broke or fixed, slower
real-time delivery, slower `run` steps and pages (Faza 14). What a release changed on screen was not compared: a
button that moved, a block that disappeared, a layout that broke on a phone. Rule 11 allows no new library, rule 2
says code decides, rule 5 says nothing without evidence, rule 12 forbids anything drawn on the site's pictures.
Screenshots of tester steps are poor material: an AI drove the page there, at a moment no other run shares.

## Decision
- **A look is its own evidence, taken by code.** `site_health` has an opt-in check `look`: for each page and screen
  of its job the session settles the page (top of the page, focus and selection cleared, web fonts, a lazy scroll
  through the captured part, images decoded, a 500 ms network-quiet window, all within `look_settle_ms`), then
  captures it at CSS scale down to `look_max_height` (default 4000 CSS px, 0: the first screen), a few frames 500 ms
  apart (equal bytes: steady) and again after a second load (`look_loads`, a plain GET of the same address).
  Animations are disabled and the caret hidden for the screenshot only; nothing is injected into the page.
  A second load that lands on another page or answers another status gives no look.
- **Storage.** Frames are `ArtifactType.VISUAL` artifacts (never a step's screenshot or the live board's tile) on the
  look's own sub-action; a `page_look` row keeps what was read with them: landed path, status, page height, viewport,
  renderer (`chromium <version>; <os> <arch>; headless|headed`), settled or not, loaded fonts, masked areas by reason,
  and elements a mask could name. Older databases get the table empty.
- **Masks are measured, never painted.** Areas not compared are recorded as boxes, cut to where they actually show:
  the owner's `target_profile.visual.mask` (selector keys or plain CSS, at most 50), the step's `look_mask`, elements
  marked `data-petek-mask`, video and frames of another origin, dates and times of day written on the page, and the
  run's own texts (testers' names and e-mails, the company code, the run's mark; never a password, never stored as
  values). A change next to the run's own text is "the run's own content" only where that text's other length explains
  it (the rest of the line moved sideways); a recolour or a new element beside a tester's name still counts.
- **The diff is JDK only** (`javax.imageio` PNG decoding and `BufferedImage` pixel arrays, kept to reporting
  infrastructure; domain and application never import `java.awt`/`javax.imageio`, checked by `ArchitectureTest`).
  Rows are aligned by hashes of their pixels (an inserted or removed band moves what is below it, not everything),
  pixels compared with pixelmatch's YIQ colour distance (0.10) and a symmetric 1 px shift tolerance, 8 × 8 cells,
  regions of at least 2 cells, bands of at least 8 rows. What moves by itself inside a run (frames of one load, the
  reload, other testers' looks of the same page) is noise and ignored. A band at the bottom of a capture is a
  height-cap artifact only when the other capture was cut by its cap.
- **Verdict by code.** Per step, page and screen: UNCHANGED, CHANGED (code proved a difference in every pair of
  samples, or the page lands on another page, i.e. another number of path segments or another last segment, `;`
  parameters such as a session id dropped, or answers another status; a path part that may be the run's own data, such
  as a test company's slug, is only a fact), NOT_COMPARABLE with a reason (another browser or
  system, another screen, the page kept moving, mostly masked, not settled, a missing frame, a frame whose sha256 no
  longer matches its record, 429/503 answers, a look missing on one side), ADDED or REMOVED with the scenario.
- **The gate is the owner's:** `report` by default, a changed look is shown but the comparison is not worse; `fail`
  (`petek compare --visual fail`, the panel's `?visual=fail`, MCP `compare_runs.visual`) counts it as worse (exit 1).
- **Derived pictures, not evidence.** The overlay, before/after/difference crops and `visual.json` (the inputs by
  artifact id and verified sha256, the outputs by sha256: a cache) are written under
  `report/visual/<baseline run>/` beside `compare-<baseline run>.html`; frames are linked where they are, never copied.
- **A look's time is Pətək's.** The waits of a look (the page settling, its frames, the second load) are left out of the
  step's speed, so a busy page never makes a step "slower"; the page's own timing is `perf`'s.
- **Drafts** start their setup with one look step (`public-look`, or `site-look` on a site without sign-in) of the
  visitor's pages on phone, tablet and desktop before anyone signs in or writes, carrying the first-visit timing too.

## Consequences
+ A release that moves, hides or breaks something on a screen is caught by code, shown with the pictures that prove it.
+ No new library; the page is never touched; the pictures stay the site's own.
- A different Chromium, system or font set makes every look not comparable until a new baseline is taken on the same
  machine or image.
- Content earlier runs left on public pages (feeds, counters) changes until it is masked; the report suggests masks.
- Frames take disk space (about 0.2–1.5 MB each); they are not pruned yet.
- An older Pətək cannot read a database with VISUAL artifacts.
