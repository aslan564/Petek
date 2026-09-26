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

package az.petek.campaign.domain

import java.util.regex.PatternSyntaxException

/**
 * Which of an actor's own requests decide whether it won a race (`only_one_succeeds: {request: ...}`): YAML
 * `"<METHOD> <path regex>"`, e.g. `"POST .+/approve"`. [method] is one of [MUTATING_METHODS], or null for any of them
 * (written `*`); [pathRegex] must match the whole URL path (no query string), so `.+/approve` matches
 * `/tickets/t2/approve` and `/api/tickets/t2/approve` but not `/approve-all`. Only mutating requests are recorded by
 * the browser, so a read (`GET`) can never decide a race; the validator refuses such a pattern.
 */
data class RequestPattern(
    val method: String?,
    val pathRegex: String,
) {
    private val compiled: Regex? by lazy {
        try {
            Regex(pathRegex)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    /** Whether a request with [method] to [path] is one this pattern describes; false for an invalid [pathRegex]. */
    fun matches(
        method: String,
        path: String,
    ): Boolean {
        val verb = method.uppercase()
        if (verb !in MUTATING_METHODS || (this.method != null && this.method != verb)) return false
        return compiled?.matches(path) == true
    }

    /** The YAML form, e.g. `POST .+/approve` (`*` for any mutating method). */
    fun describe(): String = "${method ?: ANY_METHOD} $pathRegex"

    companion object {
        /** The methods whose requests the browser records; a race is decided by one of these. */
        val MUTATING_METHODS: Set<String> = linkedSetOf("POST", "PUT", "PATCH", "DELETE")

        /** Written in place of a method: any of [MUTATING_METHODS]. */
        const val ANY_METHOD = "*"

        /** The default when `only_one_succeeds` names no request: every mutating request to the target. */
        val ANY_MUTATION = RequestPattern(null, ".*")

        /**
         * Reads `"<METHOD> <path regex>"`; the method is upper-cased (`*` = any mutating method). Null when [text] is
         * not two parts separated by whitespace. The method and the regex are checked by the validator, which reports
         * them with a line number.
         */
        fun parse(text: String): RequestPattern? {
            val parts = text.trim().split(WHITESPACE, limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) return null
            val method = parts[0].uppercase().takeUnless { it == ANY_METHOD }
            return RequestPattern(method, parts[1].trim())
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
