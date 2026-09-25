package az.petek.llm.application

import az.petek.core.ids.AgentId
import az.petek.llm.domain.LlmResponse
import java.util.TreeMap

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

    /** Current totals per agent id, sorted by agent number (`a99` before `a100`), other labels after them. */
    fun snapshot(): Map<String, UsageTotals> = synchronized(lock) { sorted(totals) }

    /** Sum over every agent. */
    fun total(): UsageTotals = snapshot().values.fold(UsageTotals.EMPTY, UsageTotals::plus)

    /** Returns the current totals and resets the meter in one atomic step, so no call is counted twice or lost. */
    fun drain(): Map<String, UsageTotals> =
        synchronized(lock) {
            val drained = sorted(totals)
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

    private fun sorted(totals: Map<String, UsageTotals>): Map<String, UsageTotals> =
        TreeMap<String, UsageTotals>(KEY_ORDER).apply {
            putAll(totals)
        }

    companion object {
        /** The agent id a [label] is accounted to. */
        fun agentKey(label: String): String = label.substringBefore('/')

        /** Agent ids by number (their text order would put `a100` before `a99`), then any other key by its text. */
        private val KEY_ORDER: Comparator<String> =
            compareBy<String, AgentId?>(nullsLast()) { AgentId.parseOrNull(it) }.thenBy { it }
    }
}
