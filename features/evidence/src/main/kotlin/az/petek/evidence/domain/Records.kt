/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.evidence.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.core.ids.WorkspaceId
import java.time.Instant

/*
 * The evidence model (docs/PLAN.md "Sübut bazası"). No verdict exists without evidence (AGENTS.md rule 5):
 * every assertion links to at least one artifact (screenshot) or an oracle response.
 */

enum class RunResult { RUNNING, PASSED, FAILED, ABORTED }

data class RunRecord(
    val runId: RunId,
    val runTag: RunTag,
    val campaignHash: String,
    val campaignName: String,
    val seed: Long,
    val target: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val result: RunResult = RunResult.RUNNING,
    /** Runs started together by `--repeat N` share a group id. */
    val repeatGroup: String? = null,
    val repeatIndex: Int? = null,
    /** Always [WorkspaceId.LOCAL] on the owner's machine (ADR-0011). */
    val workspaceId: WorkspaceId = WorkspaceId.LOCAL,
    /**
     * The site's release the run tested, as the owner named it (`petek run --release v1.4.2`); runs of one scenario are
     * compared by it (the regression baseline, Faza 14). Null: not named.
     */
    val release: String? = null,
)

/** External objects a run created on the target (e.g. the test company) so teardown can remove them. */
data class RunResource(
    val runId: RunId,
    val kind: String,
    val externalId: String,
    val createdAt: Instant,
)

enum class StepKind { DO, RUN, WAIT, EMIT, ASSERT, SYSTEM }

/**
 * The action of the harness step that records a campaign's `coverage:` lines at a run's start, one line each, so the
 * run's report names what its scenario left unchecked (the owner's decision of 2026-09-30). Not `coverage`: that is the
 * runner's record of which receivers of a wave could wait for an event.
 */
const val COVERAGE_ACTION = "scenario_coverage"

/**
 * How a page look's sub-action is named after its function (`site_health: look at /pricing (phone)`). Its time is mostly
 * Pətək's own waiting for the page to settle, so a step's speed never counts it (the regression baseline, Faza 14).
 */
const val LOOK_ACTION = "look at "

/** Whether [action] (a step record's action) is a page look's sub-action ([LOOK_ACTION]). */
fun isLookAction(action: String): Boolean = action.substringAfter(": ").startsWith(LOOK_ACTION)

enum class StepStatus { PASSED, FAILED, SKIPPED, BLOCKED, ERROR }

/** One action of one agent (or of the harness when [agentId] is null). */
data class StepRecord(
    val stepId: StepId,
    val runId: RunId,
    val agentId: AgentId?,
    /** Scenario step id from the campaign, e.g. `announce`. */
    val scenarioStep: String,
    val kind: StepKind,
    /** Human-readable action, e.g. `click #12 "Elan yarat"` or `run register_and_login`. */
    val action: String,
    /** The LLM's stated reason for a `do` action; null for deterministic steps. */
    val llmReason: String?,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMs: Long,
    val status: StepStatus,
    val detail: String?,
    val correlationId: CorrelationId,
)

data class EventRecord(
    val eventId: EventId,
    val runId: RunId,
    val name: String,
    val emitter: AgentId,
    val objectId: String?,
    /** Where [objectId] came from: `url_regex`, `oracle`, `dom`, `agent_report`. */
    val objectIdSource: String?,
    val payloadJson: String,
    val t0: Instant,
)

/** A receiver seeing (or not seeing) an event on screen. [latencyMs] = t1 − t0 measured by the harness. */
data class EventReceipt(
    val eventId: EventId,
    val runId: RunId,
    val receiver: AgentId,
    val received: Boolean,
    val t1: Instant?,
    val latencyMs: Long?,
)

/**
 * How fast one page became usable for one tester, as the browser itself timed it (Navigation Timing and paint entries,
 * read by code, never told by the AI; AGENTS.md rules 1 and 2): `site_health`'s `perf` check records one per page and
 * screen. Milliseconds from the start of the navigation; null where the browser did not report it (e.g. no largest
 * contentful paint on an empty page). Releases are compared by them (the regression baseline, Faza 14).
 */
data class PageTimingRecord(
    val runId: RunId,
    val stepId: StepId,
    val agentId: AgentId,
    val scenarioStep: String,
    /** The page's path on the target, as the step asked for it (`/announcements`). */
    val page: String,
    /** `phone`, `tablet` or `desktop` when the step chose a screen; null: the session's own. */
    val device: String?,
    /** Time to the first byte of the page's answer. */
    val ttfbMs: Long?,
    val domContentLoadedMs: Long?,
    val loadMs: Long?,
    /** Largest contentful paint: when the page's main content showed. */
    val largestPaintMs: Long?,
    /** Cumulative layout shift: how much the page jumped while it loaded (unitless; under 0.1 is good). */
    val layoutShift: Double?,
    val recordedAt: Instant,
)

/** A box on a page look, in CSS pixels of its captured image (`0,0` is the page's top-left corner). */
data class LookBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Why an area of a page look is not compared between releases (the look's masks, recorded by code, never painted). */
enum class LookMaskReason {
    /** A selector of the owner's target profile (`target_profile.visual.mask`). */
    PROFILE,

    /** A selector the step named (`site_health`'s `look_mask`). */
    STEP,

    /** An element the site marked `data-petek-mask` (docs/TARGET_CONTRACT.md). */
    MARKUP,

    /** The run's own texts on the page: the testers' names and e-mails, the company code, the run's mark. */
    RUN_TEXT,

    /** A date or a time of day written on the page. */
    TIME_TEXT,

    /** Video and frames of another origin: not what the site itself drew. */
    EMBED,
}

/** One masked area of a look; [source] names it (a selector key, or the kind of run text, never the text itself). */
data class LookMask(
    val box: LookBox,
    val reason: LookMaskReason,
    val source: String,
)

enum class LookFrameKind {
    /** The look itself: the page once it stopped changing, or after the settle budget. */
    MAIN,

    /** A frame of the same load that differed from [MAIN]: what moves by itself. */
    MOVED,

    /** The page after a reload, when it differed from [MAIN]: what changes from load to load. */
    RELOADED,
}

/** One captured image of a look, stored as the [ArtifactType.VISUAL] artifact [artifactId]. */
data class LookFrame(
    val artifactId: ArtifactId,
    val kind: LookFrameKind,
    val width: Int,
    val height: Int,
    val masks: List<LookMask>,
)

/** An element a mask could name ([selector]: a test id or a stable id), for the comparison's mask suggestions only. */
data class LookAnchor(
    val selector: String,
    val box: LookBox,
)

/**
 * One look of a page on one screen (`site_health`'s `look`, docs/adr/0014): how the page looked to a tester, taken by
 * code, kept as [ArtifactType.VISUAL] frames on the look's own sub-action [stepId]. Releases are compared by them
 * (`petek compare`, the regression baseline, Faza 14). [frames] starts with the [LookFrameKind.MAIN] frame.
 */
data class PageLookRecord(
    val runId: RunId,
    val stepId: StepId,
    val agentId: AgentId,
    val scenarioStep: String,
    /** The page's path on the target, as the step asked for it. */
    val page: String,
    /** `phone`, `tablet` or `desktop` when the step chose a screen; null: the session's own. */
    val device: String?,
    /** The path the browser ended on (the site may have redirected it). */
    val landedPath: String,
    /** The HTTP status of the page's own answer, when the browser reported it. */
    val status: Int?,
    val viewportWidth: Int,
    val viewportHeight: Int,
    /** The document's whole height, before the look's height cap. */
    val pageHeight: Int,
    /** The cap the look was taken with, in CSS pixels; 0: the first screen only. */
    val maxHeight: Int,
    /** How many testers shared the step (visited-link colours can differ when this does). */
    val testers: Int,
    /** Browser, version, system and mode, e.g. `chromium 141.0; Mac OS X aarch64; headless`. */
    val renderer: String,
    /** The page finished loading (fonts, images, network) within the settle budget. */
    val settled: Boolean,
    /** What had not finished when [settled] is false: `network`, `fonts`, `images`. */
    val unsettled: List<String>,
    /** The loaded font faces, `family weight style`, sorted. */
    val fonts: List<String>,
    val frames: List<LookFrame>,
    val anchors: List<LookAnchor>,
    val recordedAt: Instant,
)

/** Kind of a stored artifact; [extension] is the file extension it is written with, so viewers open it right. */
enum class ArtifactType(
    val extension: String,
) {
    SCREENSHOT("png"),
    A11Y("yaml"),
    DOM("html"),

    /** An `http_status` call as plain text, `<status> <body>`; the body need not be JSON, so the file is not either. */
    HTTP("txt"),
    MAIL("json"),

    /** The oracle's JSON answer. */
    ORACLE("json"),
    PROMPT("txt"),
    LOG("txt"),

    /**
     * A page look's frame (`site_health`'s `look`, [PageLookRecord]): kept apart from [SCREENSHOT] so a look never
     * stands for its step's screenshot in the report or on the live board.
     */
    VISUAL("png"),
}

data class ArtifactRecord(
    val artifactId: ArtifactId,
    val runId: RunId,
    val stepId: StepId,
    val type: ArtifactType,
    /** Path relative to the evidence root, e.g. `run_…/a07/0003-screenshot.png`. */
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Source of truth for the three-source comparison: A sender log, B receiver screen, C target oracle. */
enum class EvidenceSource { SENDER, RECEIVER, ORACLE, HARNESS }

/**
 * [NOT_APPLICABLE] is an oracle check on a target without a test API: not a skipped test but a supported mode
 * ("N/A (no oracle)", Faza 10); the other sources still judge the step.
 *
 * [INCONCLUSIVE] is a check that ran but whose evidence cannot decide it (Faza 24.12): a race nobody attempted, one with
 * a single racer or with requests that could not be read, a text the receiver's page showed before the change was
 * written, a latency known only as a range around its limit. It says nothing against the site, so it is no site
 * finding (the "tool gap" shelf); nor does it prove anything, so a run with one is not PASSED.
 */
enum class Verdict { PASSED, FAILED, SKIPPED, NOT_APPLICABLE, INCONCLUSIVE }

data class AssertionRecord(
    val stepId: StepId,
    val runId: RunId,
    val agentId: AgentId?,
    val scenarioStep: String,
    val type: String,
    val source: EvidenceSource,
    val expected: String,
    val observed: String?,
    val verdict: Verdict,
    val latencyMs: Long?,
    val note: String?,
    val artifactIds: List<ArtifactId>,
)

/**
 * How a race (`only_one_succeeds`) ended, as the lead word of its assertion's note, so the judge and the reports tell
 * the cases apart without reading prose (the owner's decisions of 2026-09-30).
 */
object RaceNotes {
    /** More than one racer won by the site's own answers: the site decided the same thing twice, a site defect. */
    const val SEVERAL_WINNERS = "several_winners"

    /** Only one racer sent the deciding request: the site was never asked two decisions at once, so nothing was proved. */
    const val UNCONTESTED = "uncontested"
}

/**
 * What a finding is about. [SITE_CHECK]: a deterministic check of the site itself (blind `site_health`, `direct_url`)
 * saw it go wrong — a page wider than a phone, a script error, a broken link, a page open to a role that must not
 * see it; the check's own words say what. [INCONCLUSIVE]: the checks of a step had no evidence to decide with
 * ([Verdict.INCONCLUSIVE]); their notes say what was missing.
 */
enum class FindingClass { BACKEND, DELIVERY_UI, SITE_CHECK, INVESTIGATE, FLAKY, AGENT_FAILURE, INCONCLUSIVE }

/**
 * How strong a finding's proof is (Faza 10), shown next to every finding: the target's own test API confirmed it
 * ([ORACLE_CONFIRMED]); the harness saw it on screen or on the network ([UI_NETWORK]); or it rests on a model's reading
 * of the page ([LLM_JUDGED], e.g. a tester that gave up), which a person should check against the screenshot.
 */
enum class EvidenceTier { ORACLE_CONFIRMED, UI_NETWORK, LLM_JUDGED }

data class FindingRecord(
    val findingId: FindingId,
    val runId: RunId,
    val stepId: StepId?,
    val scenarioStep: String,
    val agentId: AgentId?,
    val findingClass: FindingClass,
    /** What the sender did (A), what receivers saw (B), what the oracle says (C). */
    val a: String?,
    val b: String?,
    val c: String?,
    val note: String,
    val artifactIds: List<ArtifactId>,
    val workspaceId: WorkspaceId = WorkspaceId.LOCAL,
    val evidenceTier: EvidenceTier = EvidenceTier.UI_NETWORK,
)

/** Token and cost accounting per agent (LLM usage is reported, never estimated by the LLM). */
data class UsageRecord(
    val runId: RunId,
    val agentId: AgentId,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val costUsd: Double?,
    val calls: Int,
)
