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

package az.petek.browser.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a page look asks for ([BrowserSession.look], `site_health`'s `look`, docs/adr/0014). The look is taken by code
 * for comparing releases: the page settled (fonts, images and the network quiet within [settle]), scrolled to the top,
 * captured at CSS scale (one image pixel per CSS pixel) down to [maxHeight] (0: the first screen only), [loads] times
 * (the second load is a reload). Masked areas are only measured and recorded, never painted on the page.
 */
data class LookRequest(
    val maxHeight: Int = DEFAULT_MAX_HEIGHT,
    val settle: Duration = 3.seconds,
    val loads: Int = 2,
    /** CSS selectors whose elements are not compared (the owner's profile masks, then the step's own). */
    val selectors: List<LookSelector> = emptyList(),
    /** The run's own texts that may show on the page (never a password); where they show is not compared. */
    val runTexts: List<RunText> = emptyList(),
    /** The run's mark (`RunTags.forRun`): texts like `<tag>-<n>` the run wrote are its own content. */
    val runTag: String? = null,
) {
    init {
        require(maxHeight in 0..MAX_HEIGHT) { "maxHeight must be in 0..$MAX_HEIGHT, was $maxHeight" }
        require(loads in 1..2) { "loads must be 1 or 2, was $loads" }
        require(settle.isPositive()) { "settle must be positive, was $settle" }
    }

    companion object {
        const val DEFAULT_MAX_HEIGHT = 4_000
        const val MAX_HEIGHT = 16_000
    }
}

/** A mask selector and where it came from ([source]: the profile's selector key, or `look_mask:<key>`). */
data class LookSelector(
    val source: String,
    val css: String,
)

/** One of the run's own texts, by [kind] (`tester_name`, `tester_email`, `company_code`, …); never printed. */
data class RunText(
    val kind: String,
    val text: String,
) {
    override fun toString(): String = "RunText($kind, ***)"
}

/** Why an area of a look is not compared. */
enum class LookAreaReason { PROFILE, STEP, MARKUP, RUN_TEXT, TIME_TEXT, EMBED }

/** An area of a look, in CSS pixels of the captured image; [source] names it, never with a run text's value. */
data class LookArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val reason: LookAreaReason,
    val source: String,
)

enum class LookShotKind {
    /** The look: the page's final frame of the first load. */
    MAIN,

    /** An earlier frame of the first load that differed from [MAIN]: something moved by itself. */
    MOVED,

    /** The final frame after a reload, when it differed from [MAIN]. */
    RELOADED,
}

/** One captured frame: a PNG of [width] × [height] CSS pixels, with the areas not to compare in it. */
class LookShot(
    val png: ByteArray,
    val kind: LookShotKind,
    val width: Int,
    val height: Int,
    val areas: List<LookArea>,
)

/** An element a mask could name (a test id or a stable id selector) and its box, for mask suggestions only. */
data class PageAnchor(
    val selector: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** A look of the current page ([BrowserSession.look]): its frames, [MAIN][LookShotKind.MAIN] first, and what was read with them. */
class PageLook(
    val shots: List<LookShot>,
    val viewport: Viewport,
    /** The document's whole height (`scrollHeight`), before the height cap. */
    val pageHeight: Int,
    /** The path the browser ended on. */
    val landedPath: String,
    /** The status of the page's own answer (Navigation Timing `responseStatus`), when the browser reported it. */
    val status: Int?,
    /** Browser, version, system and mode, e.g. `chromium 141.0; Mac OS X aarch64; headless`. */
    val renderer: String,
    /** The page finished loading within the settle budget. */
    val settled: Boolean,
    /** What had not finished: `network`, `fonts`, `images`. */
    val unsettled: List<String>,
    /** Loaded font faces, `family weight style`, sorted. */
    val fonts: List<String>,
    val anchors: List<PageAnchor>,
    /** Mask selectors the page could not use (`querySelectorAll` refused them). */
    val rejectedSelectors: List<String>,
)
