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

package az.petek.scenarios.domain

import az.petek.core.security.Secret

/** Removes secrets from text before it is shown to the model or stored as triage evidence (CLAUDE.md rule 10). */
fun interface TextRedactor {
    fun redact(text: String): String
}

/**
 * Masks every known [secrets] value (test token, API keys, test passwords), then values that look secret whatever
 * they are: `password=…`, `token: …`, `"token": "…"` (JSON), `Authorization: Bearer …`, and the key formats of the AI providers (Anthropic, OpenAI, Google, Groq, xAI, Hugging Face). The producers
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
                // Provider key formats: Anthropic and OpenAI-style (`sk-ant-`, `sk-proj-`, `sk-or-v1-`, `sk-`), Google (`AIza`),
                // Groq (`gsk_`), xAI (`xai-`), Hugging Face (`hf_`).
                Regex("\\bsk-(?:ant-|proj-|or-v1-)?[A-Za-z0-9_-]{16,}") to MASK,
                Regex("\\bAIza[0-9A-Za-z_-]{30,}") to MASK,
                Regex("\\bgsk_[A-Za-z0-9]{20,}") to MASK,
                Regex("\\bxai-[A-Za-z0-9]{20,}") to MASK,
                Regex("\\bhf_[A-Za-z0-9]{20,}") to MASK,
                Regex(
                    "(?i)\\b(password|passwd|pwd|token|x-test-token|api[_-]?key|secret|authorization)" +
                        "([\"']?\\s*[:=]\\s*)([\"']?)(?!\\*\\*\\*)(?!\\{)[^\\s\"',;]+",
                ) to "$1$2$3$MASK",
            )
    }
}
