/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure

import kotlinx.serialization.json.JsonObject

/**
 * The "schema in the prompt" mode for providers without native structured output: the system text gets the JSON
 * Schema and the demand for exactly one JSON object; [StructuredJson] reads the answer and the caller's code
 * validation (e.g. the agent's decision protocol) does the rest. A malformed answer is retried once by
 * `RetryingLlmClient`.
 */
internal object SchemaPrompt {
    fun system(
        system: String,
        schema: JsonObject,
    ): String =
        buildString {
            append(system.trimEnd())
            append("\n\n")
            append(INSTRUCTION)
            append('\n')
            append(schema.toString())
        }

    const val INSTRUCTION: String =
        "Answer with exactly one JSON object that matches the following JSON Schema, and nothing else: no Markdown " +
            "fence, no text before or after it."
}
