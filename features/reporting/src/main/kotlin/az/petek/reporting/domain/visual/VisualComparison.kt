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

package az.petek.reporting.domain.visual

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.LookBox

/*
 * How two releases looked (docs/adr/0014): page looks of the same step, page and screen compared by code, pixel by
 * pixel, after aligning their rows. Code decides every verdict (AGENTS.md rule 2); the pictures are the site's own
 * captures and the difference picture is derived from them, never evidence of its own.
 */

/** Whether a changed look makes the comparison worse: [REPORT] (the default) only shows it, [FAIL] counts it. */
enum class VisualGate { REPORT, FAIL }

/**
 * When pixels, cells, regions and bands count as changed, and when a look is not comparable at all. Colour distance is
 * pixelmatch's YIQ measure: two colours differ when `delta > 35215 × colorDelta²`.
 */
data class VisualThresholds(
    /** YIQ colour distance, 0..1 (pixelmatch's scale). */
    val colorDelta: Double = 0.10,
    /** A pixel matching a neighbour this far away on the other side (both ways) is the same: anti-aliasing, subpixels. */
    val shiftRadius: Int = 1,
    /** Cell size in pixels. */
    val cell: Int = 8,
    /** Differing pixels that make a cell changed. */
    val cellPixels: Int = 4,
    /** Touching changed cells that make a region; fewer are speckle, counted only in the totals. */
    val regionCells: Int = 2,
    /** Rows with compared pixels that make an inserted or removed band count. */
    val bandRows: Int = 8,
    /** Pixels added around every mask. */
    val halo: Int = 2,
    /** Differing pixels that make a cell noise when a page is compared with itself (another frame, another tester). */
    val noiseCellPixels: Int = 1,
    /** Cells added around every noise cell. */
    val noiseGrow: Int = 1,
    /** A side whose moving and peer cells exceed this share of its cells kept moving: not comparable. */
    val movingLimit: Double = 0.30,
    /** A side whose ignored pixels exceed this share of its capture is mostly ignored: not comparable. */
    val ignoredLimit: Double = 0.60,
    /** A page height change of at least this many pixels is named as a fact (never a change by itself). */
    val heightFact: Int = 8,
    /** Captures per side: the representative and its peers. */
    val maxSamples: Int = 3,
) {
    init {
        require(colorDelta > 0.0 && colorDelta <= 1.0) { "colorDelta must be in (0, 1], was $colorDelta" }
        require(shiftRadius in 0..MAX_SHIFT) { "shiftRadius must be in 0..$MAX_SHIFT, was $shiftRadius" }
        require(cell in 2..MAX_CELL) { "cell must be in 2..$MAX_CELL, was $cell" }
        require(cellPixels in 1..cell * cell) { "cellPixels must be in 1..${cell * cell}, was $cellPixels" }
        require(regionCells >= 1) { "regionCells must be positive, was $regionCells" }
        require(bandRows >= 1) { "bandRows must be positive, was $bandRows" }
        require(halo in 0..MAX_HALO) { "halo must be in 0..$MAX_HALO, was $halo" }
        require(noiseCellPixels in 1..cell * cell) { "noiseCellPixels must be in 1..${cell * cell}, was $noiseCellPixels" }
        require(noiseGrow in 0..MAX_GROW) { "noiseGrow must be in 0..$MAX_GROW, was $noiseGrow" }
        require(movingLimit > 0.0 && movingLimit <= 1.0) { "movingLimit must be in (0, 1], was $movingLimit" }
        require(ignoredLimit > 0.0 && ignoredLimit <= 1.0) { "ignoredLimit must be in (0, 1], was $ignoredLimit" }
        require(heightFact >= 0) { "heightFact must not be negative, was $heightFact" }
        require(maxSamples in 1..MAX_SAMPLES) { "maxSamples must be in 1..$MAX_SAMPLES, was $maxSamples" }
    }

    /** The squared YIQ distance above which two colours differ. */
    val maxDelta: Double get() = YIQ_MAX * colorDelta * colorDelta

    companion object {
        /** Names the algorithm and its version in every manifest; a change of either recomputes cached comparisons. */
        const val ALGORITHM = "petek-visual/1"
        private const val YIQ_MAX = 35_215.0
        private const val MAX_SHIFT = 3
        private const val MAX_CELL = 64
        private const val MAX_HALO = 64
        private const val MAX_GROW = 8
        private const val MAX_SAMPLES = 9
    }
}

/** How one look changed from the baseline to the current run. */
enum class LookChange {
    UNCHANGED,

    /** Code proved the page looks different (or lands elsewhere, or answers another status). */
    CHANGED,

    /** The two looks cannot be compared ([LookReason] says why); never a regression. */
    NOT_COMPARABLE,

    /** Only the current run looked, and its scenario added the step. */
    ADDED,

    /** Only the baseline looked, and the current scenario removed the step. */
    REMOVED,
}

/** Why a look is [LookChange.NOT_COMPARABLE]. */
enum class LookReason {
    /** The baseline did not look at this page on this screen in this step. */
    MISSING_BEFORE,

    /** The current run did not look at it. */
    MISSING_NOW,

    /** Every capture of a side answered 429 or 503: the surroundings, not the site. */
    SURROUNDINGS,

    /** The browser, its version or the system differs. */
    OTHER_BROWSER,

    /** The screen size differs. */
    OTHER_SCREEN,

    /** Too much of the page changed by itself within a run ([VisualThresholds.movingLimit]). */
    KEPT_MOVING,

    /** Too much of the page is masked or moving ([VisualThresholds.ignoredLimit]). */
    MOSTLY_IGNORED,

    /** The pages differ, but one of them had not finished loading. */
    NOT_SETTLED,

    /** A frame's file is missing or cannot be read as an image. */
    NO_IMAGE,

    /** A frame's file no longer has the hash recorded with it. */
    EVIDENCE_ALTERED,
}

enum class LookFactKind {
    /** The page now lands on another path. */
    LANDED,

    /** The page's own answer has another HTTP status. */
    STATUS,

    /** The page's height changed by at least [VisualThresholds.heightFact]. */
    HEIGHT,

    /** The loaded font faces differ: [LookFact.before] lists the ones gone, [LookFact.after] the new ones. */
    FONTS,

    /** A different number of testers shared the step (visited-link colours can differ then). */
    TESTERS,
}

/** Something the two looks' records differ in; [LANDED][LookFactKind.LANDED] and [STATUS][LookFactKind.STATUS] make a change. */
data class LookFact(
    val kind: LookFactKind,
    val before: String,
    val after: String,
)

enum class BandKind { INSERTED, REMOVED }

/**
 * Rows only one side has. [INSERTED][BandKind.INSERTED]: [y] and [height] in the current capture. [REMOVED][BandKind.REMOVED]:
 * [y] and [height] in the baseline, and [at] the current row where they were. [counted] when at least
 * [VisualThresholds.bandRows] of its rows were compared ([liveRows]) and it is neither the run's own content nor
 * [cutByLimit] (a band at the bottom of a capture that hit its height cap, which is the cap and not the page).
 */
data class Band(
    val kind: BandKind,
    val y: Int,
    val height: Int,
    val at: Int,
    val liveRows: Int = height,
    val runContent: Boolean = false,
    val cutByLimit: Boolean = false,
    val counted: Boolean = false,
)

/**
 * Touching changed cells: [box] bounds their differing pixels (current capture's coordinates) and [pixels] counts them.
 * A region over the run's own texts is [runContent]: shown, never counted.
 */
data class ChangedRegion(
    val box: LookBox,
    val pixels: Long,
    val cells: Int,
    val runContent: Boolean = false,
) {
    val counted: Boolean get() = !runContent
}

/** Why pixels of a look were not compared: a mask's reason, or noise. */
enum class IgnoredBy {
    PROFILE,
    STEP,
    MARKUP,
    RUN_TEXT,
    TIME_TEXT,
    EMBED,

    /** Changed by itself within one load or between two loads of the same run. */
    MOVING,

    /** Differed between the testers of one run. */
    PEERS,
}

/** A look's identity across runs: the scenario step, the page as the step asked for it, and the screen. Never the tester. */
data class LookKey(
    val scenarioStep: String,
    val page: String,
    val device: String?,
)

/** One side's representative capture of a look and what was read with it; [samples] counts the captures used. */
data class LookSide(
    val runId: RunId,
    val agentId: AgentId,
    val stepId: StepId,
    val artifactId: ArtifactId,
    /** The frame's path relative to the evidence root, as recorded. */
    val relativePath: String,
    /** The frame's recorded hash. */
    val sha256: String,
    /** The file on disk hashed to [sha256] when the comparison read it. */
    val verified: Boolean,
    val renderer: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val pageHeight: Int,
    val landedPath: String,
    val status: Int?,
    val settled: Boolean,
    val samples: Int,
)

/** A crop of a changed area: the baseline's, the current run's and the difference picture's, as report-relative links. */
data class LookCrop(
    val number: Int,
    /** The area in its own capture's coordinates (the baseline's for removed rows). */
    val box: LookBox,
    val before: String,
    val after: String,
    val diff: String,
)

/** A look's pictures as links relative to the report directory; the frames are linked where they are, never copied. */
data class LookFiles(
    val overlay: String?,
    val before: String?,
    val after: String?,
    val crops: List<LookCrop> = emptyList(),
)

/** One look in both runs, as the comparison decided it (see [LookChange]). [index] numbers it from 1. */
data class LookComparison(
    val index: Int,
    val key: LookKey,
    val change: LookChange,
    val reason: LookReason? = null,
    /** The share behind [LookReason.KEPT_MOVING] (moving cells) or [LookReason.MOSTLY_IGNORED] (ignored pixels). */
    val reasonShare: Double? = null,
    val facts: List<LookFact> = emptyList(),
    val before: LookSide? = null,
    val after: LookSide? = null,
    val regions: List<ChangedRegion> = emptyList(),
    val bands: List<Band> = emptyList(),
    /** Pixel pairs compared (neither side ignored them). */
    val comparedPixels: Long = 0,
    /** Differing pixels of counted regions. */
    val changedPixels: Long = 0,
    /** Every differing pixel, speckle and the run's own content included. */
    val differingPixels: Long = 0,
    /** The current capture's pixels, as compared. */
    val capturedPixels: Long = 0,
    /** The current capture's pixels not compared, by the first reason that covers them. */
    val ignored: Map<IgnoredBy, Long> = emptyMap(),
    /** Pairs of captures compared (representatives and samples). */
    val pairs: Int = 0,
    /** YAML-quoted selectors that could mask the counted regions; never applied by Pətək. */
    val suggestions: List<String> = emptyList(),
    val files: LookFiles? = null,
) {
    val countedRegions: List<ChangedRegion> get() = regions.filter { it.counted }
    val countedBands: List<Band> get() = bands.filter { it.counted }

    /** The counted regions' share of the compared pixels. */
    val changedShare: Double get() = if (comparedPixels == 0L) 0.0 else changedPixels.toDouble() / comparedPixels

    /** The share of the current capture not compared. */
    val ignoredShare: Double get() = if (capturedPixels == 0L) 0.0 else ignored.values.sum().toDouble() / capturedPixels
}
