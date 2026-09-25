package az.petek.llm.application

import az.petek.llm.domain.LlmResponse

/**
 * Thread-safe usage totals keyed by agent id, i.e. the part of [az.petek.llm.domain.LlmRequest.label] before the
 * first `/` (`a07/announce` counts for `a07`; a label without `/` is its own key).
 *
 * Totals are cumulative until [drain] hands them over; draining at the end of each run makes every flush carry only
 * that run's usage, which matters because the evidence store adds a flush to the totals it already has.
 */
class UsageMeter {
    private val lock = Any()
    private val totals = HashMap<String, UsageTotals>()

    /** Counts one successful call with its tokens and (when reported) cost. */
    fun record(
        label: String,
        response: LlmResponse,
    ) = add(label, UsageTotals(calls = 1, tokens = response.usage, costUsd = response.costUsd))

    /** Counts one call that ended in an error (after any retries). */
    fun recordFailure(label: String) = add(label, UsageTotals(failedCalls = 1))

    /** Current totals per agent id, sorted by agent id. */
    fun snapshot(): Map<String, UsageTotals> = synchronized(lock) { totals.toSortedMap() }

    /** Sum over every agent. */
    fun total(): UsageTotals = snapshot().values.fold(UsageTotals.EMPTY, UsageTotals::plus)

    /** Returns the current totals and resets the meter in one atomic step, so no call is counted twice or lost. */
    fun drain(): Map<String, UsageTotals> =
        synchronized(lock) {
            val drained = totals.toSortedMap()
            totals.clear()
            drained
        }

    private fun add(
        label: String,
        delta: UsageTotals,
    ) {
        val key = agentKey(label)
        synchronized(lock) {
            totals[key] = (totals[key] ?: UsageTotals.EMPTY) + delta
        }
    }

    companion object {
        /** The agent id a [label] is accounted to. */
        fun agentKey(label: String): String = label.substringBefore('/')
    }
}
