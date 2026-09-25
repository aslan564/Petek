package az.petek.verification.testing

import az.petek.oracle.domain.JsonFieldSelector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Test stand-in for the oracle's selector: dotted paths with optional indexes, e.g. `history[0].to`. */
class SimpleJsonFieldSelector : JsonFieldSelector {
    private val segment = Regex("""([^.\[\]]+)((?:\[\d+])*)""")
    private val index = Regex("""\[(\d+)]""")

    override fun select(
        root: JsonElement,
        path: String,
    ): JsonElement? =
        path.split('.').fold(root as JsonElement?) { current, part ->
            val match = segment.matchEntire(part) ?: throw IllegalArgumentException("Bad path segment '$part' in '$path'")
            val named = (current as? JsonObject)?.get(match.groupValues[1])
            index.findAll(match.groupValues[2]).fold(named) { element, i ->
                (element as? JsonArray)?.getOrNull(i.groupValues[1].toInt())
            }
        }
}
