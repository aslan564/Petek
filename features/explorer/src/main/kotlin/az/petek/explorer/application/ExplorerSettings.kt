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

package az.petek.explorer.application

import az.petek.core.model.WorkingLanguage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning of the explorer that is not part of an owner's request. The defaults suit a small web application such as
 * a company portal; the composition root may override them.
 *
 * @property seedPaths well-known sign-in and sign-up paths tried besides the links the target shows; a guess that
 *   answers with an error is dropped silently (it was not a broken link, just a wrong guess).
 * @property slowPageMs a page load at or above this is a [az.petek.explorer.domain.FindingKind.SLOW_PAGE] (MEDIUM),
 *   at or above [verySlowPageMs] HIGH.
 * @property maxLinkChecks HTTP status checks of discovered links per crawl pass (broken-link detection), beyond the
 *   pages actually visited.
 * @property maxConsecutiveLlmFailures after this many failed LLM calls in a row the explorer stops asking the LLM and
 *   continues with what code finds, noting it in the summary.
 * @property phoneWidth the phone screen every visited page is measured at (with [phoneHeight]); a page wider than it
 *   by more than [mobileTolerancePx] is a [az.petek.explorer.domain.FindingKind.MOBILE_OVERFLOW].
 * @property liveEffectTimeout how long other roles' pages are watched for a trial touch's marker text.
 * @property language what the analyst writes purposes, questions and ideas in ([WorkingLanguage.AUTO]: the owner's
 *   own language, else the page's).
 */
data class ExplorerSettings(
    val seedPaths: List<String> = listOf("/login", "/register", "/signup", "/join"),
    val slowPageMs: Long = 3_000,
    val verySlowPageMs: Long = 8_000,
    val maxLinkChecks: Int = 200,
    val phoneWidth: Int = 375,
    val phoneHeight: Int = 812,
    val mobileTolerancePx: Int = 1,
    val maxActionsPerPage: Int = 25,
    val maxUnknownsPerPage: Int = 3,
    val maxConsecutiveLlmFailures: Int = 3,
    val promptMaxElements: Int = 120,
    val promptMaxTextChars: Int = 3_000,
    val llmMaxOutputTokens: Int = 1_500,
    val liveEffectTimeout: Duration = 3.seconds,
    val respectRobotsTxt: Boolean = true,
    val sessionLabel: String = "explorer",
    val maxRoles: Int = 20,
    /**
     * How long a page that loaded empty is given to render before it is captured: single-page applications answer
     * `load` with an empty shell and draw the page afterwards (seen on a real single-page application, 2026-09-26). Polled every [pageSettlePoll].
     */
    val pageSettleTimeout: Duration = 4.seconds,
    val pageSettlePoll: Duration = 250.milliseconds,
    val language: WorkingLanguage = WorkingLanguage.AUTO,
) {
    init {
        require(seedPaths.all { it.startsWith("/") && !it.startsWith("//") }) { "seed paths must be paths on the target" }
        require(slowPageMs in 1..verySlowPageMs) { "slowPageMs must be positive and at most verySlowPageMs" }
        require(maxLinkChecks >= 0) { "maxLinkChecks must not be negative" }
        require(maxActionsPerPage > 0 && maxUnknownsPerPage >= 0) { "per-page limits must be positive" }
        require(maxConsecutiveLlmFailures > 0) { "maxConsecutiveLlmFailures must be positive" }
        require(liveEffectTimeout.isPositive()) { "liveEffectTimeout must be positive" }
        require(maxRoles > 0) { "maxRoles must be positive" }
        require(!pageSettleTimeout.isNegative() && pageSettlePoll.isPositive()) {
            "pageSettleTimeout must not be negative and pageSettlePoll must be positive"
        }
    }
}
