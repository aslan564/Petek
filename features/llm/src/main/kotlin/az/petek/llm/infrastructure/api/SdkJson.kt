package az.petek.llm.infrastructure.api

import com.anthropic.core.JsonValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Bridges kotlinx.serialization JSON (our contracts) to the SDK's Jackson-backed [JsonValue] without losing values. */
internal object SdkJson {
    fun toSdk(element: JsonElement): JsonValue = JsonValue.from(toPlain(element))

    private fun toPlain(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonObject -> element.mapValues { (_, value) -> toPlain(value) }
            is JsonArray -> element.map(::toPlain)
            is JsonPrimitive -> primitive(element)
        }

    private fun primitive(value: JsonPrimitive): Any =
        when {
            value.isString -> value.content
            else -> value.booleanOrNull ?: value.longOrNull ?: value.content.toBigDecimalOrNull() ?: value.content
        }
}
