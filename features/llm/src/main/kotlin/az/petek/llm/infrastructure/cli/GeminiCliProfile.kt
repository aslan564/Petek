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

package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.SchemaPrompt
import az.petek.llm.infrastructure.StructuredJson
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * `gemini -p` with `--output-format json`. The Gemini CLI has no structured-output flag, so the schema travels in the
 * prompt ([SchemaPrompt]); the whole task goes through STDIN, which the CLI puts before the short `-p` instruction.
 * The JSON envelope carries `response`, `stats.models.<model>.tokens` and, on failure, `error`.
 */
internal class GeminiCliProfile(
    private val config: CliAgentConfig,
) : CliAgentProfile {
    override val provider: LlmProviderKey = LlmProviderKey.GEMINI_CLI
    override val model: String = config.modelLabel
    override val displayName: String = "Gemini CLI"
    override val scratchPrefix: String = "petek-gemini-"

    override fun call(
        request: LlmRequest,
        scratch: Path,
    ): CliCall {
        val command =
            buildList {
                add(config.executable)
                addAll(listOf("-p", INSTRUCTION))
                addAll(listOf("--output-format", "json"))
                config.model?.let { addAll(listOf("-m", it)) }
            }
        return CliCall(command, CliTranscripts.withSystem(SchemaPrompt.system(request.system, request.responseSchema), request.messages))
    }

    override fun parse(
        output: ProcessOutput,
        scratch: Path,
        environment: Map<String, String>,
        label: String,
    ): LlmResponse {
        val envelope = StructuredJson.parseObject(output.stdout)
        val error = CliOutputs.obj(envelope, "error")
        val text = CliOutputs.string(envelope, "response")
        if (envelope == null || error != null || text == null) {
            val detail = CliOutputs.string(error, "message") ?: output.stderr.ifBlank { output.stdout }
            throw CliOutputs.failure(displayName, LOGIN_HINT, detail, label)
        }
        val answer =
            StructuredJson.parseObject(text)
                ?: throw LlmException.InvalidOutput("$displayName answer for $label is not a JSON object", raw = text)
        val models = CliOutputs.obj(CliOutputs.obj(envelope, "stats"), "models")
        return LlmResponse(answer, usage(models), models?.keys?.firstOrNull() ?: model, costUsd = null)
    }

    private fun usage(models: JsonObject?): TokenUsage =
        models.orEmpty().values.fold(TokenUsage()) { total, entry ->
            val tokens = CliOutputs.obj(entry, "tokens")
            val cached = CliOutputs.long(tokens, "cached")
            total +
                CliOutputs.usage(
                    input = (CliOutputs.long(tokens, "prompt") - cached).coerceAtLeast(0),
                    output = CliOutputs.long(tokens, "candidates"),
                    cacheRead = cached,
                )
        }

    companion object {
        const val LOGIN_HINT = "Run `gemini` once and sign in with your own Google account, or set GEMINI_API_KEY."
        const val INSTRUCTION = "Do the task given above and answer with only the JSON object it asks for."
    }
}
