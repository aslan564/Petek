/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.domain

import az.petek.core.security.Secret

/** Removes secrets from text before it is shown to the model or stored as triage evidence (CLAUDE.md rule 10). */
fun interface TextRedactor {
    fun redact(text: String): String
}

/**
 * Masks every known [secrets] value (test token, API keys, test passwords), then values that look secret whatever
 * they are: `password=…`, `token: …`, `"token": "…"` (JSON), `Authorization: Bearer …`, Anthropic keys. The producers
 * of evidence already redact what they know (the agent types `{self.password}`); this is the last line before text
 * reaches the model.
 * Secrets shorter than [MIN_SECRET_LENGTH] are ignored, because masking them would mangle ordinary words.
 */
class SecretRedactor(
    secrets: Collection<Secret> = emptyList(),
) : TextRedactor {
    private val values: List<String> =
        secrets
            .map { it.reveal() }
            .filter { it.length >= MIN_SECRET_LENGTH }
            .distinct()
            .sortedByDescending { it.length }

    override fun redact(text: String): String {
        var result = text
        values.forEach { result = result.replace(it, MASK) }
        return PATTERNS.fold(result) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
    }

    companion object {
        const val MASK: String = "***"
        const val MIN_SECRET_LENGTH: Int = 4

        private val PATTERNS: List<Pair<Regex, String>> =
            listOf(
                Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 $MASK",
                Regex("sk-ant-[A-Za-z0-9_-]{8,}") to MASK,
                Regex(
                    "(?i)\\b(password|passwd|pwd|token|x-test-token|api[_-]?key|secret|authorization)" +
                        "([\"']?\\s*[:=]\\s*)([\"']?)(?!\\*\\*\\*)(?!\\{)[^\\s\"',;]+",
                ) to "$1$2$3$MASK",
            )
    }
}
