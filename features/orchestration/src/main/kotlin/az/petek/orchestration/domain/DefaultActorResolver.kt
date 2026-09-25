package az.petek.orchestration.domain

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.ActorSelector
import az.petek.identity.domain.Identity

/**
 * Resolves actor expressions with the grammar documented on [ActorExpression]:
 * - a selector keeps the identities of its role, optionally narrowed by department (case-insensitive, `*` = any)
 *   and registration mode;
 * - `nth` is the 1-based position inside that narrowed list ordered by agent id, so `employee[dept=IT, n=2]` is
 *   stable for a given registry; an index past the end (or below 1) selects nobody instead of failing the run;
 * - the expression is the union of its selectors, de-duplicated and ordered by agent id.
 *
 * Agent ids are compared by their numeric index, so `a100` sorts after `a99`.
 */
class DefaultActorResolver : ActorResolver {
    override fun resolve(
        expression: ActorExpression,
        identities: List<Identity>,
    ): List<Identity> {
        val ordered = identities.sortedBy { it.agentId.index }
        return expression.selectors
            .flatMap { select(it, ordered) }
            .distinctBy { it.agentId }
            .sortedBy { it.agentId.index }
    }

    private fun select(
        selector: ActorSelector,
        ordered: List<Identity>,
    ): List<Identity> {
        val matching = ordered.filter { matches(selector, it) }
        val nth = selector.nth ?: return matching
        return listOfNotNull(matching.getOrNull(nth - 1))
    }

    private fun matches(
        selector: ActorSelector,
        identity: Identity,
    ): Boolean =
        identity.role == selector.role &&
            departmentMatches(selector.department, identity.department) &&
            (selector.registration == null || identity.registration == selector.registration)

    private fun departmentMatches(
        wanted: String?,
        actual: String?,
    ): Boolean {
        val filter = wanted?.trim()
        if (filter.isNullOrEmpty() || filter == WILDCARD) return true
        return actual != null && actual.trim().equals(filter, ignoreCase = true)
    }

    private companion object {
        const val WILDCARD = "*"
    }
}
