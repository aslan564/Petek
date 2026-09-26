/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI's strict structured-output mode accepts a schema only when every object lists all its properties as
 * `required` and forbids others. [of] rewrites Pətək's schemas into that form: optional properties become required
 * but nullable, `additionalProperties` becomes `false`. [withoutNulls] turns the answer back: a `null` the model gave
 * for an optional property is removed, so the parsers read it as "absent", as they always have.
 */
internal object StrictSchema {
    fun of(schema: JsonObject): JsonObject = strict(schema) as JsonObject

    fun withoutNulls(answer: JsonObject): JsonObject = dropNulls(answer) as JsonObject

    private fun strict(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> strictObject(element)
            is JsonArray -> JsonArray(element.map(::strict))
            else -> element
        }

    private fun strictObject(schema: JsonObject): JsonObject {
        val rewritten = schema.mapValues { (key, value) -> if (key == PROPERTIES) value else strict(value) }.toMutableMap()
        val properties = schema[PROPERTIES] as? JsonObject
        if (properties != null) {
            val required =
                (schema[REQUIRED] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.toSet()
                    .orEmpty()
            rewritten[PROPERTIES] =
                JsonObject(
                    properties.mapValues { (name, property) ->
                        val strictProperty = strict(property)
                        if (name in required) strictProperty else nullable(strictProperty)
                    },
                )
            rewritten[REQUIRED] = JsonArray(properties.keys.map(::JsonPrimitive))
            rewritten[ADDITIONAL] = JsonPrimitive(false)
        }
        return JsonObject(rewritten)
    }

    /** `{"type": "string"}` → `{"type": ["string", "null"]}`; anything else → `{"anyOf": [it, {"type": "null"}]}`. */
    private fun nullable(property: JsonElement): JsonElement {
        if (property !is JsonObject) return property
        return when (val type = property[TYPE]) {
            is JsonPrimitive -> {
                if (type.contentOrNull == NULL) property else JsonObject(property + (TYPE to JsonArray(listOf(type, JsonPrimitive(NULL)))))
            }

            is JsonArray -> {
                if (type.any { (it as? JsonPrimitive)?.contentOrNull == NULL }) {
                    property
                } else {
                    JsonObject(property + (TYPE to JsonArray(type + JsonPrimitive(NULL))))
                }
            }

            else -> {
                JsonObject(mapOf(ANY_OF to JsonArray(listOf(property, JsonObject(mapOf(TYPE to JsonPrimitive(NULL)))))))
            }
        }
    }

    private fun dropNulls(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.filterValues { it != JsonNull }.mapValues { (_, value) -> dropNulls(value) })
            is JsonArray -> JsonArray(element.map(::dropNulls))
            else -> element
        }

    private const val PROPERTIES = "properties"
    private const val REQUIRED = "required"
    private const val ADDITIONAL = "additionalProperties"
    private const val TYPE = "type"
    private const val ANY_OF = "anyOf"
    private const val NULL = "null"
}
