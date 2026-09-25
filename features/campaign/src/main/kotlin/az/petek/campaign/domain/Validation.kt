package az.petek.campaign.domain

import az.petek.core.error.PetekException

data class ValidationIssue(
    val line: Int?,
    val message: String,
) {
    override fun toString(): String = if (line != null) "line $line: $message" else message
}

class CampaignValidationException(
    val issues: List<ValidationIssue>,
) : PetekException("Campaign is invalid:\n" + issues.joinToString("\n") { "  - $it" })

/** Parses actor expressions; throws [CampaignValidationException] with the offending text on bad input. */
interface ActorExpressionParser {
    fun parse(
        raw: String,
        line: Int? = null,
    ): ActorExpression

    fun parseList(
        items: List<String>,
        line: Int? = null,
    ): ActorExpression
}

/**
 * Cross-field rules: role quota sums to testers, registration quota sums to non-admins and invites at least every
 * manager (managers always join by invitation), departments non-empty,
 * unique step ids, every `wait_for` refers to an event emitted by an earlier step, `only_one_succeeds` only on
 * `parallel` steps with 2+ actors, known `run` functions, templates only use known placeholders.
 */
interface CampaignValidator {
    fun validate(
        campaign: Campaign,
        knownRunFunctions: Set<String>,
    ): List<ValidationIssue>
}
