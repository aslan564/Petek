package az.petek.verification.domain

import az.petek.browser.domain.ObservedMutation
import az.petek.campaign.domain.RequestPattern

/**
 * What one actor's own requests say about its attempt in a race (`only_one_succeeds`). Code decides from the requests
 * the browser saw the target answer (CLAUDE.md rule 2); the agent's `done(success)` and summary are only text.
 *
 * Rules, over [requests] (the actor's matching mutating requests during its action, oldest first):
 * - [succeeded]: at least one request was accepted (status < 400) and none was refused (403, 409, 422). With a broad
 *   pattern (the default: every mutating request) an unrelated accepted request, such as a notification marked read,
 *   therefore never outweighs the refusal of the approval itself. One exception keeps a double submit from turning a
 *   winner into a loser: a refusal of the same method and path the actor had already won is the target answering the
 *   actor's own duplicate, so it does not count.
 * - [decisive]: the request that decided: the first counted refusal, else the first accepted request, else the last
 *   request (e.g. a 500), else none.
 * - [refusedAsDecided]: the actor did not succeed and the target refused it with 409 or 422, i.e. the object had
 *   already been decided, typically by the winner. A 403 is a permission refusal, not a lost race.
 *
 * [unavailable] says why the requests could not be read (e.g. the browser session broke); such an actor did not
 * succeed, for lack of evidence.
 */
data class RaceEvidence(
    val requests: List<ObservedMutation>,
    val unavailable: String? = null,
) {
    /** The refusals that count: every one except a repeat of a request the actor had already won. */
    private val refusals: List<ObservedMutation> by lazy {
        requests.filterIndexed { index, request ->
            request.status in REFUSED &&
                requests.take(index).none { it.status < ACCEPTED_BELOW && it.method == request.method && it.path == request.path }
        }
    }

    val succeeded: Boolean get() = requests.any { it.status < ACCEPTED_BELOW } && refusals.isEmpty()

    val decisive: ObservedMutation?
        get() = refusals.firstOrNull() ?: requests.firstOrNull { it.status < ACCEPTED_BELOW } ?: requests.lastOrNull()

    val refusedAsDecided: Boolean get() = !succeeded && decisive?.status in CONFLICTS

    /** `POST /tickets/t2/approve -> 409`, `no matching request` or why the requests are unknown. */
    fun describe(): String =
        decisive?.describe()
            ?: unavailable?.let { "requests unavailable: $it" }
            ?: NO_REQUEST

    companion object {
        /** Statuses meaning the object was already decided by someone else. */
        val CONFLICTS: Set<Int> = setOf(409, 422)

        /** Statuses meaning the target refused the actor's attempt. */
        val REFUSED: Set<Int> = CONFLICTS + 403

        /** Statuses below this are accepted answers, redirects after a form post included. */
        const val ACCEPTED_BELOW = 400

        const val NO_REQUEST = "no matching request"

        /** The evidence of [observed] requests for [pattern]: only matching ones count. */
        fun of(
            pattern: RequestPattern,
            observed: List<ObservedMutation>,
        ): RaceEvidence = RaceEvidence(observed.filter { pattern.matches(it.method, it.path) })

        /** No evidence at all: the actor's requests could not be read ([reason]). */
        fun unavailable(reason: String): RaceEvidence = RaceEvidence(emptyList(), reason)
    }
}
