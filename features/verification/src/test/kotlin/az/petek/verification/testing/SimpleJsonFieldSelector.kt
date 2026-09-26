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
