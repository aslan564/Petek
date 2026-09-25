/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.oracle.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Path syntax: keys separated by dots, each optionally followed by `[index]`s: `status`, `assignee.email`,
 * `history[0].to`, `receipts[2]`, `matrix[1][0]`, `[0].id` (root array). A negative index counts from the end, so
 * `history[-1].to` is the latest transition. A purely numeric key also indexes an array (`items.0`). A blank path
 * selects the root; surrounding whitespace is ignored.
 *
 * Missing keys, out-of-range indexes and stepping into a primitive give Kotlin `null` ("not there"); a key that is
 * present with JSON `null` gives [JsonNull]. A malformed path (`a..b`, `a[x]`, `a[1`) is a campaign mistake, not a
 * missing value, so it throws [IllegalArgumentException] naming the path instead of silently reading as "missing".
 */
class DefaultJsonFieldSelector : JsonFieldSelector {
    override fun select(
        root: JsonElement,
        path: String,
    ): JsonElement? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return root
        return parse(trimmed).fold<Step, JsonElement?>(root) { current, step -> current?.let(step::applyTo) }
    }

    private fun parse(path: String): List<Step> =
        path.split('.').flatMapIndexed { position, part ->
            val segment = SEGMENT.matchEntire(part) ?: invalid(path, "'$part' is not a key followed by optional [index] parts")
            val (key, indexes) = segment.destructured
            if (key.isEmpty() && (position > 0 || indexes.isEmpty())) invalid(path, "a key is missing")
            buildList {
                if (key.isNotEmpty()) add(Step.Key(key))
                INDEX.findAll(indexes).forEach { add(Step.Index(it.groupValues[1].toIntOrNull() ?: OUT_OF_RANGE)) }
            }
        }

    private fun invalid(
        path: String,
        reason: String,
    ): Nothing = throw IllegalArgumentException("Invalid field path '$path': $reason")

    private sealed interface Step {
        fun applyTo(element: JsonElement): JsonElement?

        data class Key(
            val name: String,
        ) : Step {
            override fun applyTo(element: JsonElement): JsonElement? =
                when {
                    element is JsonObject -> element[name]
                    element is JsonArray && name.all(Char::isDigit) -> element.at(name.toIntOrNull() ?: OUT_OF_RANGE)
                    else -> null
                }
        }

        data class Index(
            val index: Int,
        ) : Step {
            override fun applyTo(element: JsonElement): JsonElement? = (element as? JsonArray)?.at(index)
        }
    }

    private companion object {
        /** Stands in for indexes too large for an Int: syntactically fine, never present. */
        const val OUT_OF_RANGE = Int.MAX_VALUE
        val SEGMENT = Regex("""([^\[\]]*)((?:\[-?\d+])*)""")
        val INDEX = Regex("""\[(-?\d+)]""")

        fun JsonArray.at(index: Int): JsonElement? = getOrNull(if (index < 0) size + index else index)
    }
}
