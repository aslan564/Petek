/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The JSON-RPC 2.0 envelope MCP rides on (one message per line over stdio). A message with an `id` is a request that
 * gets exactly one response; one without is a notification that gets none. Error codes are the standard ones.
 */
internal data class JsonRpcMessage(
    /** Null for a notification; a request's id is echoed as it came (number or string). */
    val id: JsonElement?,
    val method: String?,
    val params: JsonObject,
) {
    val isNotification: Boolean get() = id == null

    companion object {
        fun parse(root: JsonElement): JsonRpcMessage {
            val obj = root as? JsonObject ?: throw JsonRpcException(JsonRpcError.INVALID_REQUEST, "a JSON object was expected")
            if ((obj["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
                throw JsonRpcException(JsonRpcError.INVALID_REQUEST, "jsonrpc must be \"2.0\"", obj["id"])
            }
            val id = obj["id"]?.takeUnless { it is JsonNull }
            val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val params =
                obj["params"]?.let {
                    it as? JsonObject
                        ?: throw JsonRpcException(JsonRpcError.INVALID_PARAMS, "params must be an object", id)
                }
            return JsonRpcMessage(id, method, params ?: JsonObject(emptyMap()))
        }
    }
}

internal object JsonRpcError {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/** A failure that becomes a JSON-RPC error response (a protocol problem, not a tool's own result). */
internal class JsonRpcException(
    val code: Int,
    message: String,
    val id: JsonElement? = null,
) : RuntimeException(message)

internal fun jsonRpcResult(
    id: JsonElement?,
    result: JsonElement,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }

internal fun jsonRpcError(
    id: JsonElement?,
    code: Int,
    message: String,
): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

// --- reading tool arguments ------------------------------------------------------------------------------------------

internal fun JsonObject.stringArgument(
    name: String,
    required: Boolean = true,
): String? {
    val value = this[name]?.takeUnless { it is JsonNull } ?: if (required) throw invalidArgument("$name is required") else return null
    val primitive = value as? JsonPrimitive ?: throw invalidArgument("$name must be a string")
    if (!primitive.isString) throw invalidArgument("$name must be a string")
    return primitive.content
}

internal fun JsonObject.intArgument(name: String): Int? {
    val value = this[name]?.takeUnless { it is JsonNull } ?: return null
    return (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: throw invalidArgument("$name must be an integer")
}

internal fun JsonObject.booleanArgument(name: String): Boolean {
    val value = this[name]?.takeUnless { it is JsonNull } ?: return false
    return when ((value as? JsonPrimitive)?.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> throw invalidArgument("$name must be true or false")
    }
}

internal fun JsonObject.stringsArgument(name: String): List<String>? {
    val value = this[name]?.takeUnless { it is JsonNull } ?: return null
    val array = value as? kotlinx.serialization.json.JsonArray ?: throw invalidArgument("$name must be an array of strings")
    return array.map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: throw invalidArgument("$name must be an array of strings")
    }
}

internal fun JsonObject.objectArgument(name: String): JsonObject? = this[name]?.takeUnless { it is JsonNull }?.let { it.jsonObject }

internal fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content

private fun invalidArgument(message: String) = JsonRpcException(JsonRpcError.INVALID_PARAMS, message)
