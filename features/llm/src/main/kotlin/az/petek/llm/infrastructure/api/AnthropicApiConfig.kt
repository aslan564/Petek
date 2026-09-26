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
