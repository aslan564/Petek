package az.petek.scenarios.domain

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings

/**
 * What a triage proposal may change. A proposal fixes the site knowledge (`target_profile`) or the steps, never who
 * tests against what: the campaign name, target, testers, seed, names, roles, departments and registration split
 * must stay as they are. Only `budget` and `on_fail` may change, because a too-small step budget is a scenario bug.
 *
 * The settings are compared as loaded, so with a target override (`PETEK_TARGET`) a changed `campaign.target` line
 * is not visible here; the owner still sees it in the diff before approving.
 */
object ProposalRules {
    fun violations(
        parent: Campaign,
        child: Campaign,
    ): List<String> {
        val before = parent.settings
        val after = child.settings
        if (before.name != after.name) {
            return listOf("the change renames the scenario from '${before.name}' to '${after.name}'")
        }
        val changed = PROTECTED.filter { (_, value) -> value(before) != value(after) }.map { it.first }
        return if (changed.isEmpty()) {
            emptyList()
        } else {
            listOf("the change touches campaign settings a proposal must keep: ${changed.joinToString(", ")}")
        }
    }

    private val PROTECTED: List<Pair<String, (CampaignSettings) -> Any?>> =
        listOf(
            "target" to { it.target },
            "testers" to { it.testers },
            "seed" to { it.seed },
            "names" to { it.names },
            "roles" to { it.roles },
            "departments" to { it.departments },
            "registration" to { it.registration },
        )
}
