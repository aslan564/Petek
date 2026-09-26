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

package az.petek.llm.testing

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.TokenUsage
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic LLM for tests: [responder] maps each request to a JSON answer (it can read the prompt,
 * e.g. to find an element ref in the rendered snapshot). Every request is kept in [requests].
 */
class ScriptedLlmClient(
    override val model: String = "scripted",
    private val usagePerCall: TokenUsage = TokenUsage(inputTokens = 100, outputTokens = 20),
    private val responder: suspend (LlmRequest) -> JsonObject,
) : LlmClient {
    override val provider: LlmProviderKey = SCRIPTED
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return LlmResponse(responder(request), usagePerCall, model, costUsd = 0.0)
    }

    companion object {
        /** Neutral: a scripted answer is no provider's. */
        val SCRIPTED: LlmProviderKey = checkNotNull(LlmProviderKey.of("scripted"))
    }
}
