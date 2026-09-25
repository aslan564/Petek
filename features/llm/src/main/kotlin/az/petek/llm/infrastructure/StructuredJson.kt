package az.petek.llm.infrastructure

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads the JSON object a model wrote as text. Structured output normally yields bare JSON, but a model may still
 * wrap it in a Markdown fence or add a sentence around it, so this accepts (in order): the bare text, the text inside
 * a ``` fence, and the span from the first `{` to the last `}`. Anything else is `null`: the caller decides how to
 * report it.
 */
internal object StructuredJson {
    fun parseObject(text: String): JsonObject? {
        val trimmed = text.trim()
        return sequenceOf(trimmed, stripFence(trimmed), outermostBraces(trimmed))
            .filterNotNull()
            .distinct()
            .firstNotNullOfOrNull { candidate -> parseOrNull(candidate) as? JsonObject }
    }

    fun parseOrNull(text: String): JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun stripFence(text: String): String? {
        if (!text.startsWith(FENCE)) return null
        val body = text.substringAfter('\n', missingDelimiterValue = "")
        return body.trimEnd().removeSuffix(FENCE).trim()
    }

    private fun outermostBraces(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    private const val FENCE = "```"
}
