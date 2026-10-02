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

package az.petek.core.model

/**
 * Which segment of a web address names one object, the rule the explorer's page patterns (`/tickets/{id}`) and the
 * testers' checks of them share: a number, a UUID, a long hex string, a short prefixed counter such as `t17`/`a3`
 * (never an API version like `v2`), a date (`2026-09-25`, `2026-09`), a numbered slug (`123-noutbuk-islemir`) or a long
 * random token (letters and digits mixed).
 */
object PathSegments {
    private val NUMBER = Regex("\\d+")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val HEX = Regex("[0-9a-fA-F]{16,}")
    private val PREFIXED_COUNTER = Regex("[A-Za-z]{1,4}[-_]?\\d+")
    private val API_VERSION = Regex("[vV]\\d+")
    private val TOKEN = Regex("[A-Za-z0-9_-]{20,}")
    private val PREFIXED_ID = Regex("[A-Za-z]{2,6}_[0-9A-Za-z]{8,}")
    private val DATE = Regex("\\d{4}-\\d{2}(-\\d{2})?")
    private val NUMBERED_SLUG = Regex("\\d+-[\\p{L}\\p{N}%-]*[\\p{L}%][\\p{L}\\p{N}%-]*")

    fun isId(segment: String): Boolean =
        when {
            segment.isEmpty() -> false
            NUMBER.matches(segment) || UUID.matches(segment) || HEX.matches(segment) -> true
            DATE.matches(segment) || NUMBERED_SLUG.matches(segment) -> true
            API_VERSION.matches(segment) -> false
            PREFIXED_COUNTER.matches(segment) || PREFIXED_ID.matches(segment) -> true
            TOKEN.matches(segment) -> segment.any(Char::isDigit) && segment.any(Char::isLetter)
            else -> false
        }

    /** [path] with each id segment written [id] and without empty segments: `/posts/17/` -> `/posts/{id}`. */
    fun generalize(
        path: String,
        id: String,
    ): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/"
        return segments.joinToString("/", prefix = "/") { if (isId(it)) id else it }
    }
}
