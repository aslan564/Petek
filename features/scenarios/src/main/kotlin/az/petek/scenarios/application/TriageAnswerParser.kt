package az.petek.scenarios.application

import az.petek.scenarios.application.TriagePrompt.Companion.CATEGORY
import az.petek.scenarios.application.TriagePrompt.Companion.CONFIDENCE
import az.petek.scenarios.application.TriagePrompt.Companion.EDITS
import az.petek.scenarios.application.TriagePrompt.Companion.EVIDENCE_REFS
import az.petek.scenarios.application.TriagePrompt.Companion.FIND
import az.petek.scenarios.application.TriagePrompt.Companion.PROPOSED_CHANGE
import az.petek.scenarios.application.TriagePrompt.Companion.RATIONALE
import az.petek.scenarios.application.TriagePrompt.Companion.REPLACE
import az.petek.scenarios.application.TriagePrompt.Companion.SUMMARY
import az.petek.scenarios.domain.TriageCategory
import az.petek.scenarios.domain.YamlEdit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A model answer that passed the checks of [TriageAnswerParser]. */
internal data class TriageAnswer(
    val category: TriageCategory,
    val rationale: String,
    val confidence: Double,
    val evidenceRefs: List<String>,
    val proposal: ProposalAnswer?,
)

/** The proposal part of an answer. A malformed proposal does not invalidate the verdict; it is rejected on its own. */
internal sealed interface ProposalAnswer {
    data class Edits(
        val summary: String,
        val edits: List<YamlEdit>,
    ) : ProposalAnswer

    data class Malformed(
        val reason: String,
    ) : ProposalAnswer
}

internal sealed interface ParsedAnswer {
    data class Valid(
        val answer: TriageAnswer,
    ) : ParsedAnswer

    data class Invalid(
        val reason: String,
    ) : ParsedAnswer
}

/**
 * Checks the model's JSON in code (the schema is a request, not a guarantee): the category is one of
 * [TriageCategory], the rationale is not blank, the confidence is a number in 0..1 and the evidence references are
 * strings. Anything else makes the whole answer invalid. Unknown fields are ignored.
 */
internal object TriageAnswerParser {
    const val MAX_RATIONALE: Int = 2_000
    const val MAX_SUMMARY: Int = 500

    fun parse(output: JsonObject): ParsedAnswer {
        val category =
            string(output[CATEGORY])
                ?.trim()
                ?.uppercase()
                ?.let { name -> TriageCategory.entries.firstOrNull { it.name == name } }
                ?: return invalid("'$CATEGORY' must be one of ${TriageCategory.entries.joinToString()}, was ${show(output[CATEGORY])}")
        val rationale =
            string(output[RATIONALE])?.trim()?.takeIf { it.isNotEmpty() }
                ?: return invalid("'$RATIONALE' must be a non-empty string")
        val confidence =
            (output[CONFIDENCE] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.doubleOrNull
                ?.takeIf { it in 0.0..1.0 }
                ?: return invalid("'$CONFIDENCE' must be a number from 0 to 1, was ${show(output[CONFIDENCE])}")
        val refs =
            when (val element = output[EVIDENCE_REFS]) {
                null, JsonNull -> emptyList()
                is JsonArray -> element.map { string(it) ?: return invalid("'$EVIDENCE_REFS' must contain strings only") }
                else -> return invalid("'$EVIDENCE_REFS' must be an array of strings")
            }
        return ParsedAnswer.Valid(
            TriageAnswer(category, clip(rationale, MAX_RATIONALE), confidence, refs, proposal(output[PROPOSED_CHANGE])),
        )
    }

    private fun proposal(element: JsonElement?): ProposalAnswer? {
        val change =
            when (element) {
                null, JsonNull -> return null
                is JsonObject -> element
                else -> return malformed("'$PROPOSED_CHANGE' must be an object")
            }
        val summary =
            when (val value = change[SUMMARY]) {
                null, JsonNull -> ""
                else -> string(value)?.trim() ?: return malformed("'$SUMMARY' must be a string")
            }
        val items = change[EDITS] as? JsonArray ?: return malformed("'$EDITS' must be an array of {find, replace} objects")
        val edits =
            items.mapIndexed { index, item ->
                val edit = item as? JsonObject ?: return malformed("edit ${index + 1} must be an object")
                val find = string(edit[FIND]) ?: return malformed("edit ${index + 1} needs a string '$FIND'")
                val replace = string(edit[REPLACE]) ?: return malformed("edit ${index + 1} needs a string '$REPLACE'")
                YamlEdit(find, replace)
            }
        return ProposalAnswer.Edits(clip(summary, MAX_SUMMARY), edits)
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun show(element: JsonElement?): String = element?.toString()?.let { clip(it, SHOWN) } ?: "missing"

    private fun clip(
        text: String,
        max: Int,
    ): String = if (text.length <= max) text else text.take(max - 1) + "…"

    private fun invalid(reason: String) = ParsedAnswer.Invalid(reason)

    private fun malformed(reason: String) = ProposalAnswer.Malformed(reason)

    private const val SHOWN = 80
}
