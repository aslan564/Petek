/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
