package az.petek.llm.application

import az.petek.llm.domain.TokenUsage

/**
 * Accumulated LLM usage of one agent (or of the whole run).
 * [costUsd] stays `null` while no call reported a cost (the Anthropic API does not), so a report can say "unknown"
 * instead of a misleading `$0.00`.
 */
data class UsageTotals(
    val calls: Long = 0,
    val failedCalls: Long = 0,
    val tokens: TokenUsage = TokenUsage(),
    val costUsd: Double? = null,
) {
    operator fun plus(other: UsageTotals): UsageTotals =
        UsageTotals(
            calls = calls + other.calls,
            failedCalls = failedCalls + other.failedCalls,
            tokens = tokens + other.tokens,
            costUsd = sumOrNull(costUsd, other.costUsd),
        )

    companion object {
        val EMPTY = UsageTotals()

        internal fun sumOrNull(
            a: Double?,
            b: Double?,
        ): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
    }
}
