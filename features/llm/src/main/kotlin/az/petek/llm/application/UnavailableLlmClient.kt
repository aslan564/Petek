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

package az.petek.llm.application

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse

/**
 * Stands in when no AI provider was found or configured: every call is [LlmException.Unavailable] with [reason] and how
 * to set one up, so a run or an exploration stops with that sentence instead of a vendor Pətək picked on its own.
 */
class UnavailableLlmClient(
    private val reason: String,
) : LlmClient {
    override val provider: LlmProviderKey = LlmProviderKey.NONE
    override val model: String = "none"

    override suspend fun complete(request: LlmRequest): LlmResponse = throw LlmException.Unavailable("No AI provider: $reason. $HOW_TO")

    companion object {
        const val HOW_TO: String =
            "Set PETEK_LLM_BIN (and PETEK_LLM_ARGS) to any AI command-line tool you are logged in to, or PETEK_LLM_BASE_URL, " +
                "PETEK_LLM_MODEL and an API key for any OpenAI-compatible API, then run `petek doctor`."
    }
}
