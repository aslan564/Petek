package az.petek.llm.infrastructure.api

import az.petek.core.security.Secret
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Settings for the Anthropic Messages API.
 *
 * @property apiKey never logged; `toString` shows `Secret(***)`.
 * @property baseUrl override for proxies and tests; `null` means the SDK default (`https://api.anthropic.com`).
 * @property timeout per HTTP request.
 * @property maxRetries retries inside the SDK. Default 0: `RetryingLlmClient` owns retrying, so attempts and delays
 *   are counted in one place.
 */
data class AnthropicApiConfig(
    val apiKey: Secret,
    val model: String,
    val baseUrl: String? = null,
    val timeout: Duration = 120.seconds,
    val maxRetries: Int = 0,
) {
    init {
        require(!apiKey.isBlank) { "Anthropic API key must not be blank (set ANTHROPIC_API_KEY)" }
        require(model.isNotBlank()) { "Anthropic model must not be blank" }
        require(baseUrl == null || baseUrl.isNotBlank()) { "Anthropic base URL must be null or non-blank" }
        require(timeout.isPositive()) { "Anthropic API timeout must be positive, was $timeout" }
        require(maxRetries >= 0) { "maxRetries must not be negative, was $maxRetries" }
    }
}
