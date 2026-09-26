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

package az.petek.faketarget.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A response as the tests read it, with small helpers to query the fake's server-rendered HTML by `data-testid`. */
data class Page(
    val status: Int,
    val body: String,
    val location: String?,
    val setCookies: List<String>,
    val contentType: String?,
) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    fun has(testId: String): Boolean = openingTags(testId).any()

    fun count(testId: String): Int = openingTags(testId).count()

    /** Text content of the first element with [testId] (tags stripped, entities decoded, trimmed). */
    fun text(testId: String): String? = texts(testId).firstOrNull()

    fun texts(testId: String): List<String> {
        val pattern = Regex("""<(\w+)(\s[^>]*)?\sdata-testid="${Regex.escape(testId)}"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(body).map { decode(it.groupValues[3].replace(TAG, "")).trim() }.toList()
    }

    /** Attribute of the first element with [testId]; also works for void elements such as `<input>`. */
    fun attribute(
        testId: String,
        name: String,
    ): String? {
        val tag = openingTags(testId).firstOrNull() ?: return null
        return Regex("""\s${Regex.escape(name)}="([^"]*)"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.let(::decode)
    }

    fun attributes(
        testId: String,
        name: String,
    ): List<String> =
        openingTags(testId)
            .mapNotNull { tag ->
                Regex("""\s${Regex.escape(name)}="([^"]*)"""")
                    .find(tag)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::decode)
            }.toList()

    /** `(value, label)` of every option of the select with [testId]. */
    fun options(testId: String): List<Pair<String, String>> {
        val select =
            Regex("""<select[^>]*data-testid="${Regex.escape(testId)}"[^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)
                ?.groupValues
                ?.get(1) ?: return emptyList()
        return Regex("""<option[^>]*value="([^"]*)"[^>]*>(.*?)</option>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(select)
            .map { decode(it.groupValues[1]) to decode(it.groupValues[2]).trim() }
            .toList()
    }

    private fun openingTags(testId: String): Sequence<String> =
        Regex("""<\w+[^>]*\sdata-testid="${Regex.escape(testId)}"[^>]*>""").findAll(body).map { it.value }

    private companion object {
        val TAG = Regex("<[^>]+>")

        fun decode(html: String): String =
            html
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&")
    }
}
