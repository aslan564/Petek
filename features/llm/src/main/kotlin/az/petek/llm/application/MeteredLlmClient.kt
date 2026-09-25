/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
