/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
