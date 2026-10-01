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

package az.petek.agent.application.runs

import az.petek.agent.domain.AgentRuntime
import az.petek.browser.domain.LookRequest
import az.petek.browser.domain.LookSelector
import az.petek.campaign.domain.CssSelectors
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.VisualProfile
import az.petek.core.ids.RunTags
import kotlin.time.Duration.Companion.milliseconds

/**
 * What `site_health`'s `look` asks the browser for, from the step's arguments and the owner's profile:
 *
 * - `look_max_height`: how far down the page is captured, in CSS pixels (default 4000, 0 to 16000; 0: the first screen
 *   only);
 * - `look_settle_ms`: how long each load may take to settle (fonts, images, the network quiet; default 3000, 500 to
 *   15000);
 * - `look_loads`: 2 (default) also reloads the page, to tell what changes from load to load; 1 does not;
 * - `look_mask`: selector keys of `target_profile.selectors` (comma-separated; never literal CSS, which may contain
 *   commas) whose elements are not compared, after the profile's own `visual.mask`.
 *
 * The run's own texts ([RunTexts]) and its mark ([RunTags]) go with every request; they are never logged.
 */
internal object LookRequests {
    const val MAX_HEIGHT = "look_max_height"
    const val SETTLE_MS = "look_settle_ms"
    const val LOADS = "look_loads"
    const val MASK = "look_mask"

    /** Where a step's mask selector came from, in the look's evidence (`look_mask:visual.clock`). */
    const val STEP_SOURCE = "look_mask:"

    private val DEFAULTS = LookRequest()
    private val SETTLE_RANGE = 500..15_000
    private val LOADS_RANGE = 1..2

    /** What is wrong with the look's arguments in [args] for a site described by [target], or null. */
    fun problem(
        target: TargetProfile,
        args: Map<String, String>,
    ): String? {
        rangeProblem(args, MAX_HEIGHT, 0..LookRequest.MAX_HEIGHT, "a whole number of CSS pixels")?.let { return it }
        rangeProblem(args, SETTLE_MS, SETTLE_RANGE, "a whole number of milliseconds")?.let { return it }
        rangeProblem(args, LOADS, LOADS_RANGE, "the number of loads")?.let { return it }
        val keys = maskKeys(args)
        if (keys.size > VisualProfile.MAX_MASKS) return "$MASK names ${keys.size} selector keys; at most ${VisualProfile.MAX_MASKS}"
        keys.forEach { key ->
            if (!target.isSelectorKey(key)) {
                return "$MASK names selector keys of target_profile.selectors (never CSS itself); there is no key '$key'"
            }
            val selector = target.resolveSelector(key)
            CssSelectors.playwrightOnly(selector)?.let { token ->
                return "$MASK key '$key' is the selector $selector, which uses '$token' that only Playwright understands; " +
                    "a look finds its masks with document.querySelectorAll, so they must be plain CSS"
            }
        }
        return null
    }

    /** The request of a step whose arguments passed [problem]. */
    fun of(
        runtime: AgentRuntime,
        args: Map<String, String>,
    ): LookRequest {
        val target = runtime.target
        val profile = target.visual.mask.map { ref -> LookSelector(ref, target.resolveSelector(ref.trim())) }
        val step = maskKeys(args).map { key -> LookSelector(STEP_SOURCE + key, target.resolveSelector(key)) }
        return LookRequest(
            maxHeight = int(args, MAX_HEIGHT) ?: DEFAULTS.maxHeight,
            settle = int(args, SETTLE_MS)?.milliseconds ?: DEFAULTS.settle,
            loads = int(args, LOADS) ?: DEFAULTS.loads,
            selectors = profile + step,
            runTexts = RunTexts.of(runtime),
            runTag = RunTags.forRun(runtime.runId).value,
        )
    }

    private fun rangeProblem(
        args: Map<String, String>,
        key: String,
        range: IntRange,
        what: String,
    ): String? {
        val raw = given(args, key) ?: return null
        val value = raw.toIntOrNull()
        return if (value == null || value !in range) "$key is $what from ${range.first} to ${range.last}, not '$raw'" else null
    }

    private fun int(
        args: Map<String, String>,
        key: String,
    ): Int? = given(args, key)?.toIntOrNull()

    private fun given(
        args: Map<String, String>,
        key: String,
    ): String? = args[key]?.trim()?.ifEmpty { null }

    private fun maskKeys(args: Map<String, String>): List<String> =
        given(args, MASK)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
