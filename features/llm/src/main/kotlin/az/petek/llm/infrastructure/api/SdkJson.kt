/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

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
