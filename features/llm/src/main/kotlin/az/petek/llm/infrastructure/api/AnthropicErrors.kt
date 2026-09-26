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

package az.petek.llm.infrastructure.api

import az.petek.llm.domain.LlmException
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.CredentialResolutionException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NoCredentialsException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import java.io.InterruptedIOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Maps SDK exceptions to [LlmException] so decorators can decide on retries without knowing the SDK.
 * Messages carry the API's own error text (never request headers, so never the key).
 */
internal class AnthropicErrors(
    private val model: String,
    private val timeout: Duration,
) {
    fun map(
        error: AnthropicException,
        label: String,
    ): LlmException =
        when (error) {
            is RateLimitException -> {
                LlmException.RateLimited(
                    "Anthropic API rate limit (HTTP 429) for $label: ${detail(error)}",
                    retryAfter = retryAfter(error.headers()),
                )
            }

            is UnauthorizedException, is PermissionDeniedException -> {
                unavailable(error, label, "the API key was rejected; check ANTHROPIC_API_KEY")
            }

            is NotFoundException -> {
                unavailable(error, label, "is the model id '$model' correct?")
            }

            is BadRequestException, is UnprocessableEntityException -> {
                unavailable(error, label, "check the model id and request settings")
            }

            is InternalServerException -> {
                transient(error, label)
            }

            is AnthropicServiceException -> {
                byStatus(error, label)
            }

            is AnthropicIoException -> {
                if (error.hasCause<InterruptedIOException>()) {
                    LlmException.Timeout("Anthropic API did not answer within $timeout for $label")
                } else {
                    LlmException.Transient("Network error calling the Anthropic API for $label: ${error.message}", error)
                }
            }

            is AnthropicRetryableException, is AnthropicInvalidDataException -> {
                LlmException.Transient("Anthropic API call for $label failed: ${error.message}", error)
            }

            is NoCredentialsException, is CredentialResolutionException -> {
                LlmException.Unavailable("Anthropic API has no usable credentials: ${error.message}", error)
            }

            else -> {
                LlmException.Transient("Anthropic API call for $label failed: ${error.message}", error)
            }
        }

    private fun byStatus(
        error: AnthropicServiceException,
        label: String,
    ): LlmException {
        val status = error.statusCode()
        return when {
            status == HTTP_TOO_MANY_REQUESTS -> {
                LlmException.RateLimited("Anthropic API rate limit for $label: ${detail(error)}", retryAfter(error.headers()))
            }

            status in RETRYABLE_CLIENT_STATUSES || status >= HTTP_SERVER_ERROR -> {
                transient(error, label)
            }

            status == HTTP_PAYMENT_REQUIRED -> {
                unavailable(error, label, "check the billing of the API key")
            }

            else -> {
                unavailable(error, label, "check the model id and request settings")
            }
        }
    }

    private fun transient(
        error: AnthropicServiceException,
        label: String,
    ) = LlmException.Transient("Anthropic API error (HTTP ${error.statusCode()}) for $label: ${detail(error)}", error)

    private fun unavailable(
        error: AnthropicServiceException,
        label: String,
        hint: String,
    ) = LlmException.Unavailable(
        "Anthropic API refused the request for $label (HTTP ${error.statusCode()}): ${detail(error)}; $hint",
        error,
    )

    /** The `error.message` of the API's JSON error body, else the SDK's message. */
    private fun detail(error: AnthropicServiceException): String {
        val body = runCatching { error.body().convert(Map::class.java) }.getOrNull()
        val message = (body?.get("error") as? Map<*, *>)?.get("message") as? String
        return (message ?: error.message ?: "no details").take(MAX_DETAIL_CHARS)
    }

    private fun retryAfter(headers: Headers): Duration? {
        headers
            .first("retry-after-ms")
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0 }
            ?.let { return it.milliseconds }
        return headers
            .first("retry-after")
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0 }
            ?.seconds
    }

    private fun Headers.first(name: String): String? = values(name).firstOrNull()?.trim()

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is T }

    private companion object {
        const val HTTP_PAYMENT_REQUIRED = 402
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        val RETRYABLE_CLIENT_STATUSES = setOf(408, 409)
        const val MAX_DETAIL_CHARS = 500
        const val MAX_CAUSE_DEPTH = 10
    }
}
