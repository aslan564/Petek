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
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Retries the failures that another attempt can fix, so agents see a provider hiccup as a slower answer instead of
 * a failed step:
 * - [LlmException.Transient] and [LlmException.Timeout]: exponential backoff (`baseDelay * 2^(n-1)`, capped at
 *   [maxDelay]) shortened by [jitter] to between 50% and 100% of that value.
 * - [LlmException.RateLimited]: waits at least the provider's `retryAfter`. A provider asking for more than [maxDelay]
 *   is not waited for: the error surfaces so the caller can report it instead of stalling an agent silently.
 * - [LlmException.InvalidOutput]: retried at most once and immediately (the model answered; waiting does not help).
 * - [LlmException.Unavailable] and non-LLM exceptions: never retried.
 *
 * [maxAttempts] counts every call, including the first one.
 */
class RetryingLlmClient(
    private val delegate: LlmClient,
    private val maxAttempts: Int = 3,
    private val baseDelay: Duration = 2.seconds,
    private val maxDelay: Duration = 30.seconds,
    private val jitter: RetryJitter = RetryJitter.DETERMINISTIC,
) : LlmClient by delegate {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
        require(!baseDelay.isNegative()) { "baseDelay must not be negative, was $baseDelay" }
        require(maxDelay >= baseDelay) { "maxDelay ($maxDelay) must not be shorter than baseDelay ($baseDelay)" }
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        var attempt = 1
        var invalidOutputRetried = false
        while (true) {
            try {
                return delegate.complete(request)
            } catch (failure: LlmException) {
                val wait = delayBeforeRetry(failure, attempt, invalidOutputRetried, request.label) ?: throw failure
                if (failure is LlmException.InvalidOutput) invalidOutputRetried = true
                logger.warn {
                    "LLM call ${request.label} failed on attempt $attempt/$maxAttempts " +
                        "(${failure::class.simpleName}: ${failure.message}); retrying in $wait"
                }
                delay(wait)
                attempt++
            }
        }
    }

    /** The wait before the next attempt, or `null` when [failure] must surface. */
    private fun delayBeforeRetry(
        failure: LlmException,
        attempt: Int,
        invalidOutputRetried: Boolean,
        label: String,
    ): Duration? {
        if (attempt >= maxAttempts) return null
        return when (failure) {
            is LlmException.Unavailable -> {
                null
            }

            is LlmException.InvalidOutput -> {
                if (invalidOutputRetried) null else Duration.ZERO
            }

            is LlmException.Transient, is LlmException.Timeout -> {
                backoff(label, attempt)
            }

            is LlmException.RateLimited -> {
                val retryAfter = failure.retryAfter
                when {
                    retryAfter == null -> backoff(label, attempt)
                    retryAfter > maxDelay -> null
                    else -> maxOf(retryAfter, backoff(label, attempt))
                }
            }
        }
    }

    /** Exponential backoff for the retry after failed [attempt] (1-based), jittered into `(50%, 100%]`. */
    internal fun backoff(
        label: String,
        attempt: Int,
    ): Duration {
        val doublings = (attempt - 1).coerceIn(0, MAX_DOUBLINGS)
        val exponential = (baseDelay * (1L shl doublings).toDouble()).coerceAtMost(maxDelay)
        val fraction = jitter.fraction(label, attempt).coerceIn(0.0, 1.0)
        return exponential * (1.0 - HALF * fraction)
    }

    private companion object {
        const val MAX_DOUBLINGS = 30
        const val HALF = 0.5
    }
}
