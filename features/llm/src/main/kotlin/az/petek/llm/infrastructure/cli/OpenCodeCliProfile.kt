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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.nio.file.Path

/**
 * `opencode run --format json` with the schema in the prompt ([SchemaPrompt]). OpenCode takes the message as its
 * argument (passed as an argument vector, never through a shell) and prints JSON events: `text` parts carry the answer,
 * `step_finish` parts the tokens and cost. Unknown event types are ignored.
 */
internal class OpenCodeCliProfile(
    private val config: CliAgentConfig,
) : CliAgentProfile {
    override val provider: LlmProviderKey = LlmProviderKey.OPENCODE_CLI
    override val model: String = config.modelLabel
    override val displayName: String = "OpenCode CLI"
    override val scratchPrefix: String = "petek-opencode-"

    override fun call(
        request: LlmRequest,
        scratch: Path,
    ): CliCall {
        val command =
            buildList {
                add(config.executable)
                add("run")
                addAll(listOf("--format", "json"))
                config.model?.let { addAll(listOf("-m", it)) }
                add(CliTranscripts.withSystem(SchemaPrompt.system(request.system, request.responseSchema), request.messages))
            }
        return CliCall(command, stdin = "")
    }

    override fun parse(
        output: ProcessOutput,
        scratch: Path,
        environment: Map<String, String>,
        label: String,
    ): LlmResponse {
        val events = CliOutputs.jsonLines(output.stdout)
        val parts = events.mapNotNull { event -> CliOutputs.obj(event, "part")?.let { CliOutputs.string(event, "type") to it } }
        val error = events.lastOrNull { CliOutputs.string(it, "type") == "error" }
        val text =
            parts
                .filter { (type, part) -> type == "text" || CliOutputs.string(part, "type") == "text" }
                .mapNotNull { (_, part) -> CliOutputs.string(part, "text") }
                .joinToString("")
                .ifBlank { null }
        if (text == null || (output.exitCode != 0 && error != null)) {
            val detail =
                error?.let { CliOutputs.string(CliOutputs.obj(it, "error"), "message") ?: it.toString() }
                    ?: output.stderr.ifBlank { output.stdout }
            throw CliOutputs.failure(displayName, LOGIN_HINT, detail, label)
        }
        val answer =
            StructuredJson.parseObject(text)
                ?: throw LlmException.InvalidOutput("$displayName answer for $label is not a JSON object", raw = text)
        val finishes =
            parts
                .filter { (type, part) ->
                    type == "step_finish" || CliOutputs.string(part, "type") == "step-finish"
                }.map { it.second }
        return LlmResponse(answer, usage(finishes), model, cost(finishes))
    }

    private fun usage(finishes: List<JsonObject>): TokenUsage =
        finishes.fold(TokenUsage()) { total, part ->
            val tokens = CliOutputs.obj(part, "tokens")
            total +
                CliOutputs.usage(
                    input = CliOutputs.long(tokens, "input"),
                    output = CliOutputs.long(tokens, "output"),
                    cacheRead = CliOutputs.long(CliOutputs.obj(tokens, "cache"), "read"),
                )
        }

    private fun cost(finishes: List<JsonObject>): Double? =
        finishes.mapNotNull { (it["cost"] as? JsonPrimitive)?.doubleOrNull }.takeIf { it.isNotEmpty() }?.sum()

    companion object {
        const val LOGIN_HINT = "Run `opencode auth login` and choose your provider."
    }
}
