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

package az.petek.browser.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sessionStorage part of a saved browser state. Playwright's storage state holds cookies and localStorage only, so
 * a site that keeps its login in sessionStorage (a common choice of single-page applications) came back signed out from
 * a saved session. Pətək adds every origin's sessionStorage to the same file, as `sessionStorage` (`[{name, value}]`)
 * next to Playwright's `localStorage` in `origins[]`; Playwright ignores the key when it loads the file, and an owner's
 * own state file may carry it too. A session puts the entries back into its tab the first time it opens a page of that
 * origin, and only then, so a site that clears them at logout stays logged out.
 */
internal object SessionStorageState {
    private const val ORIGINS = "origins"
    private const val ORIGIN = "origin"
    private const val LOCAL_STORAGE = "localStorage"
    private const val SESSION_STORAGE = "sessionStorage"

    /** Page script: the page's origin and its sessionStorage entries (`[[name, value], …]`), or null where there are none to read. */
    const val CAPTURE =
        "() => { try { if (location.protocol !== 'http:' && location.protocol !== 'https:') return null; " +
            "const entries = []; for (let i = 0; i < sessionStorage.length; i++) { const name = sessionStorage.key(i); " +
            "entries.push([name, sessionStorage.getItem(name)]); } return { origin: location.origin, entries }; } " +
            "catch (e) { return null; } }"

    /** Page script that puts entries (`[[name, value], …]`) into the page's sessionStorage. */
    const val RESTORE = "entries => { for (const [name, value] of entries) sessionStorage.setItem(name, value); }"

    /** The empty page of an origin a session opens, answered by itself, to put the entries back before the site's scripts run. */
    const val BLANK_PAGE = "<!doctype html><html><head><title></title></head><body></body></html>"

    /** The sessionStorage entries of every origin in the state file [state]; empty when it has none or cannot be read. */
    fun read(state: Path): Map<String, List<List<String>>> {
        val root = runCatching { Json.parseToJsonElement(Files.readString(state)) }.getOrNull() as? JsonObject ?: return emptyMap()
        return origins(root)
            .mapNotNull { entry ->
                val origin = text(entry[ORIGIN]) ?: return@mapNotNull null
                val entries =
                    (entry[SESSION_STORAGE] as? JsonArray).orEmpty().mapNotNull { item ->
                        val pair = item as? JsonObject ?: return@mapNotNull null
                        val name = text(pair["name"]) ?: return@mapNotNull null
                        val value = text(pair["value"]) ?: return@mapNotNull null
                        listOf(name, value)
                    }
                entries.takeIf { it.isNotEmpty() }?.let { origin to it }
            }.toMap()
    }

    /**
     * Adds [captured] (origin → entries) to the Playwright state file [state]: into the origin's entry, or as a new one
     * with an empty localStorage. Origins without entries are left as Playwright wrote them.
     */
    fun write(
        state: Path,
        captured: Map<String, List<List<String>>>,
    ) {
        val withEntries = captured.filterValues { it.isNotEmpty() }
        if (withEntries.isEmpty()) return
        val root = Json.parseToJsonElement(Files.readString(state)) as? JsonObject ?: return
        val origins = origins(root)
        val known = origins.mapNotNull { text(it[ORIGIN]) }.toSet()
        val merged =
            origins.map { entry ->
                val entries = withEntries[text(entry[ORIGIN])] ?: return@map entry
                JsonObject(entry + (SESSION_STORAGE to json(entries)))
            } +
                withEntries.filterKeys { it !in known }.map { (origin, entries) ->
                    JsonObject(
                        mapOf(ORIGIN to JsonPrimitive(origin), LOCAL_STORAGE to JsonArray(emptyList()), SESSION_STORAGE to json(entries)),
                    )
                }
        Files.writeString(state, JsonObject(root + (ORIGINS to JsonArray(merged))).toString())
    }

    private fun origins(root: JsonObject): List<JsonObject> = (root[ORIGINS] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun json(entries: List<List<String>>): JsonArray =
        JsonArray(entries.map { (name, value) -> JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value))) })
}
