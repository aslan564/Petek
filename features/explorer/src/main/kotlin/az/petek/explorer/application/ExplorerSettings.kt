package az.petek.explorer.application

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning of the explorer that is not part of an owner's request. The defaults suit a small web application such as
 * KadroHR; the composition root may override them.
 *
 * @property seedPaths well-known sign-in and sign-up paths tried besides the links the target shows; a guess that
 *   answers with an error is dropped silently (it was not a broken link, just a wrong guess).
 * @property slowPageMs a page load at or above this is a [az.petek.explorer.domain.FindingKind.SLOW_PAGE] (MEDIUM),
 *   at or above [verySlowPageMs] HIGH.
 * @property maxLinkChecks HTTP status checks of discovered links per crawl pass (broken-link detection), beyond the
 *   pages actually visited.
 * @property maxConsecutiveLlmFailures after this many failed LLM calls in a row the explorer stops asking the LLM and
 *   continues with what code finds, noting it in the summary.
 * @property liveEffectTimeout how long other roles' pages are watched for a trial touch's marker text.
 */
data class ExplorerSettings(
    val seedPaths: List<String> = listOf("/login", "/register", "/signup", "/join"),
    val slowPageMs: Long = 3_000,
    val verySlowPageMs: Long = 8_000,
    val maxLinkChecks: Int = 200,
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
) {
    init {
        require(seedPaths.all { it.startsWith("/") && !it.startsWith("//") }) { "seed paths must be paths on the target" }
        require(slowPageMs in 1..verySlowPageMs) { "slowPageMs must be positive and at most verySlowPageMs" }
        require(maxLinkChecks >= 0) { "maxLinkChecks must not be negative" }
        require(maxActionsPerPage > 0 && maxUnknownsPerPage >= 0) { "per-page limits must be positive" }
        require(maxConsecutiveLlmFailures > 0) { "maxConsecutiveLlmFailures must be positive" }
        require(liveEffectTimeout.isPositive()) { "liveEffectTimeout must be positive" }
        require(maxRoles > 0) { "maxRoles must be positive" }
    }
}
