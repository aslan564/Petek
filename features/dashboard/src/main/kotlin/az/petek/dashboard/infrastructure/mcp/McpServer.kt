/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure.mcp

import az.petek.dashboard.domain.PanelBackend
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

/**
 * The Model Context Protocol face of the panel's use cases (ADR-0009, R10): a stdio server that speaks the MCP
 * profile a host AI needs to call [McpTools] — `initialize`, `notifications/initialized`, `ping`, `tools/list`,
 * `tools/call` — as newline-delimited JSON-RPC 2.0 over [input] and [output]. Nothing but protocol goes to [output];
 * logs go to the logger (stderr and the log file). Requests run concurrently (a long `run_campaign` with `wait` does
 * not block a `ping`); responses are written one line at a time.
 *
 * Hand-rolled on purpose: the stdio profile is a few methods, and a dependency would be a rule-11 question.
 */
class McpServer(
    backend: PanelBackend,
    private val settings: McpSettings,
    private val input: InputStream,
    output: OutputStream,
) {
    private val tools = McpTools(backend, settings)
    private val out = PrintStream(output, true, StandardCharsets.UTF_8)
    private val writing = Mutex()

    /** Serves until [input] ends (the client closed the pipe) or the coroutine is cancelled. */
    suspend fun serve() {
        val reader = input.bufferedReader(StandardCharsets.UTF_8)
        supervisorScope {
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLineOrNull() } ?: break
                if (line.isBlank()) continue
                launch { handle(line) }
            }
        }
    }

    private suspend fun handle(line: String) {
        val message =
            try {
                JsonRpcMessage.parse(json.parseToJsonElement(line))
            } catch (e: SerializationException) {
                respond(jsonRpcError(null, JsonRpcError.PARSE_ERROR, "invalid JSON: ${e.message?.lineSequence()?.first()}"))
                return
            } catch (e: JsonRpcException) {
                respond(jsonRpcError(e.id, e.code, e.message.orEmpty()))
                return
            }
        val response =
            try {
                dispatch(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JsonRpcException) {
                if (message.isNotification) null else jsonRpcError(message.id, e.code, e.message.orEmpty())
            } catch (e: Exception) {
                logger.error(e) { "MCP ${message.method} failed" }
                if (message.isNotification) {
                    null
                } else {
                    jsonRpcError(message.id, JsonRpcError.INTERNAL_ERROR, "internal error; see the log")
                }
            }
        response?.let { respond(it) }
    }

    private suspend fun dispatch(message: JsonRpcMessage): JsonObject? {
        val method = message.method ?: throw JsonRpcException(JsonRpcError.INVALID_REQUEST, "method is required", message.id)
        if (message.isNotification) {
            // notifications/initialized, notifications/cancelled, ...: acknowledged silently.
            logger.debug { "MCP notification $method" }
            return null
        }
        val result: JsonElement =
            when (method) {
                "initialize" -> initialize(message.params)
                "ping" -> JsonObject(emptyMap())
                "tools/list" -> buildJsonObject { putJsonArray("tools") { tools.descriptors().forEach { add(it) } } }
                "tools/call" -> tools.call(message.params)
                else -> throw JsonRpcException(JsonRpcError.METHOD_NOT_FOUND, "unknown method $method", message.id)
            }
        return jsonRpcResult(message.id, result)
    }

    private fun initialize(params: JsonObject): JsonElement {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = requested?.takeIf { it in SUPPORTED_PROTOCOLS } ?: LATEST_PROTOCOL
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", settings.version)
            }
            put(
                "instructions",
                "Pətək tests the web application at ${settings.target} with many AI tester agents at once and reports with " +
                    "evidence. Read the project's .petek/SKILL.md for the roles (explorer, scenario author, judge, root-cause). " +
                    "Assertions are checked by Pətək's code, time by its harness; you only call the tools. " +
                    if (settings.allowWrites) {
                        "Writes (runs, approvals, teardown) are allowed in this session."
                    } else {
                        "This session is read-only: exploration and reading only."
                    },
            )
        }
    }

    private suspend fun respond(message: JsonObject) {
        val text = json.encodeToString(JsonObject.serializer(), message)
        writing.withLock {
            withContext(Dispatchers.IO) {
                out.print(text)
                out.print('\n')
                out.flush()
            }
        }
    }

    private fun BufferedReader.readLineOrNull(): String? = readLine()

    companion object {
        const val SERVER_NAME = "petek"
        const val LATEST_PROTOCOL = "2025-06-18"
        val SUPPORTED_PROTOCOLS: Set<String> = setOf("2024-11-05", "2025-03-26", LATEST_PROTOCOL)

        private val json = Json { prettyPrint = false }
    }
}
