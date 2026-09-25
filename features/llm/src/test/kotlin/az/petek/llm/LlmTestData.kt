/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

/** Shared requests, responses and a scriptable client for the llm module's tests. */
object LlmTestData {
    val DECISION_SCHEMA: JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("enum", buildJsonArray { add("click") })
                }
                putJsonObject("ref") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("action") })
            put("additionalProperties", false)
        }

    fun request(
        label: String = "a07/announce",
        messages: List<LlmMessage> = listOf(LlmMessage(LlmRole.USER, "Click the publish button")),
        system: String = "You are a careful web tester.",
        maxOutputTokens: Int = 512,
    ) = LlmRequest(
        system = system,
        messages = messages,
        responseSchema = DECISION_SCHEMA,
        maxOutputTokens = maxOutputTokens,
        label = label,
    )

    fun response(
        usage: TokenUsage = TokenUsage(inputTokens = 100, outputTokens = 20),
        costUsd: Double? = 0.01,
    ) = LlmResponse(
        output = buildJsonObject { put("action", "click") },
        usage = usage,
        model = "test-model",
        costUsd = costUsd,
    )
}

/** One scripted call: returns a response or throws. */
typealias Outcome = suspend (LlmRequest) -> LlmResponse

/**
 * Plays back [outcomes] in order: each one returns a response or throws. Records how many calls were made and,
 * through [clock], when (virtual time in coroutine tests).
 */
class OutcomeLlmClient(
    private val clock: () -> Long = { 0L },
    outcomes: List<Outcome>,
) : LlmClient {
    override val provider: LlmProviderId = LlmProviderId.ANTHROPIC_API
    override val model: String = "outcome-model"

    private val remaining = ArrayDeque(outcomes)
    val callTimes = CopyOnWriteArrayList<Long>()
    val calls: Int get() = callTimes.size

    override suspend fun complete(request: LlmRequest): LlmResponse {
        callTimes += clock()
        val next = synchronized(remaining) { remaining.removeFirstOrNull() } ?: error("No outcome left for call $calls")
        return next(request)
    }

    companion object {
        fun succeed(response: LlmResponse = LlmTestData.response()): Outcome = { response }

        fun fail(error: Throwable): Outcome = { throw error }
    }
}
