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
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import kotlin.coroutines.cancellation.CancellationException

/**
 * Records every call that reaches it in [meter]: successes with their usage, failures as failed calls.
 * Placed outermost (`MeteredLlmClient(RetryingLlmClient(ConcurrencyLimitedLlmClient(provider, n)), meter)`), it
 * counts one call per agent decision rather than one per attempt; the usage recorded is that of the successful attempt.
 * Cancelled calls are not counted: they are neither an answer nor a provider failure.
 */
class MeteredLlmClient(
    private val delegate: LlmClient,
    private val meter: UsageMeter,
) : LlmClient by delegate {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val response =
            try {
                delegate.complete(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                meter.recordFailure(request.label)
                throw failure
            }
        meter.record(request.label, response)
        return response
    }
}
