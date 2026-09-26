/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.http

import az.petek.core.security.Secret
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How the answer's JSON shape is asked for (`PETEK_LLM_STRUCTURED`); each step down is a weaker guarantee. */
enum class StructuredMode(
    val key: String,
) {
    /** `response_format: {type: json_schema, strict: true}`: the endpoint enforces the schema. */
    SCHEMA("schema"),

    /** `response_format: {type: json_object}`: valid JSON, the schema travels in the prompt. */
    JSON_OBJECT("json_object"),

    /** No `response_format`; the schema travels in the prompt and the answer is read out of the text. */
    PROMPT("prompt"),
    ;

    /** The next weaker mode, used when an endpoint rejects this one; null after [PROMPT]. */
    val fallback: StructuredMode? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromKey(key: String): StructuredMode? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * Settings of one OpenAI-compatible `chat/completions` endpoint (`PETEK_LLM_BASE_URL`, e.g. `http://localhost:11434/v1`
 * for Ollama or `https://api.openai.com/v1`).
 *
 * @property apiKey sent as `Authorization: Bearer`; null for local servers that need none. Never logged.
 * @property structured the first mode tried; an endpoint that rejects it moves to [StructuredMode.fallback].
 * @property effort `reasoning_effort` for models that support it; not sent when null.
 */
data class OpenAiCompatibleConfig(
    val baseUrl: URI,
    val model: String,
    val apiKey: Secret? = null,
    val structured: StructuredMode = StructuredMode.SCHEMA,
    val timeout: Duration = 120.seconds,
    val effort: String? = null,
) {
    init {
        require(baseUrl.scheme?.lowercase() in setOf("http", "https") && !baseUrl.host.isNullOrBlank()) {
            "the OpenAI-compatible base URL must be an absolute http(s) URL"
        }
        require(model.isNotBlank()) { "the OpenAI-compatible provider needs a model (PETEK_LLM_MODEL)" }
        require(timeout.isPositive()) { "OpenAI-compatible timeout must be positive, was $timeout" }
        require(effort == null || effort.isNotBlank()) { "effort must be null or a level such as 'low'" }
    }

    /** `<base>/chat/completions` when the base already names the API version (`.../v1`), else `<base>/v1/chat/completions`. */
    val completionsUrl: String
        get() {
            val base = baseUrl.toString().trimEnd('/')
            return if (VERSIONED.containsMatchIn(base)) "$base/chat/completions" else "$base/v1/chat/completions"
        }

    override fun toString(): String =
        "OpenAiCompatibleConfig(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey == null) "unset" else "set"}, " +
            "structured=${structured.key}, timeout=$timeout, effort=$effort)"

    private companion object {
        val VERSIONED = Regex("/v\\d+[a-z0-9]*(/openai)?$")
    }
}
