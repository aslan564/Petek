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
import az.petek.llm.infrastructure.StrictSchema
import az.petek.llm.infrastructure.StructuredJson
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * `codex exec` with native structured output: the strict form of the schema goes to `--output-schema`, the final
 * message to `--output-last-message`, the event stream (`--json`) gives the token counts. It runs read-only in the
 * empty working directory and never asks for approval; the system text leads the STDIN transcript (Codex has no
 * system-prompt flag).
 */
internal class CodexCliProfile(
    private val config: CliAgentConfig,
) : CliAgentProfile {
    override val provider: LlmProviderKey = LlmProviderKey.CODEX_CLI
    override val model: String = config.modelLabel
    override val displayName: String = "Codex CLI"
    override val scratchPrefix: String = "petek-codex-"

    override fun call(
        request: LlmRequest,
        scratch: Path,
    ): CliCall {
        val schema = Files.writeString(scratch.resolve(SCHEMA_FILE), StrictSchema.of(request.responseSchema).toString())
        val command =
            buildList {
                add(config.executable)
                add("exec")
                add("--json")
                add("--skip-git-repo-check")
                addAll(listOf("--sandbox", "read-only"))
                addAll(listOf("--output-schema", schema.toString()))
                addAll(listOf("--output-last-message", scratch.resolve(LAST_MESSAGE_FILE).toString()))
                config.model?.let { addAll(listOf("--model", it)) }
                config.effort?.let { addAll(listOf("-c", "model_reasoning_effort=\"$it\"")) }
                add("-")
            }
        return CliCall(command, CliTranscripts.withSystem(request.system, request.messages))
    }

    override fun parse(
        output: ProcessOutput,
        scratch: Path,
        environment: Map<String, String>,
        label: String,
    ): LlmResponse {
        val events = CliOutputs.jsonLines(output.stdout)
        val failure =
            events.lastOrNull { CliOutputs.string(it, "type") in FAILURE_TYPES }?.let {
                CliOutputs.string(it, "message") ?: CliOutputs.string(CliOutputs.obj(it, "error"), "message") ?: it.toString()
            }
        val lastMessageFile = scratch.resolve(LAST_MESSAGE_FILE)
        val text =
            (if (Files.exists(lastMessageFile)) Files.readString(lastMessageFile) else null)?.takeIf { it.isNotBlank() }
                ?: events
                    .mapNotNull { CliOutputs.obj(it, "item") }
                    .lastOrNull { CliOutputs.string(it, "type") == "agent_message" }
                    ?.let { CliOutputs.string(it, "text") }
        if (text == null || (output.exitCode != 0 && failure != null)) {
            throw CliOutputs.failure(displayName, LOGIN_HINT, failure ?: output.stderr.ifBlank { output.stdout }, label)
        }
        val answer =
            StructuredJson.parseObject(text)
                ?: throw LlmException.InvalidOutput("$displayName answer for $label is not a JSON object", raw = text)
        return LlmResponse(StrictSchema.withoutNulls(answer), usage(events), model, costUsd = null)
    }

    /** `turn.completed` events carry `usage: {input_tokens, cached_input_tokens, output_tokens}`. */
    private fun usage(events: List<JsonObject>): TokenUsage =
        events
            .filter { CliOutputs.string(it, "type") == "turn.completed" }
            .mapNotNull { CliOutputs.obj(it, "usage") }
            .fold(TokenUsage()) { total, usage ->
                val cached = CliOutputs.long(usage, "cached_input_tokens")
                total +
                    CliOutputs.usage(
                        input = (CliOutputs.long(usage, "input_tokens") - cached).coerceAtLeast(0),
                        output = CliOutputs.long(usage, "output_tokens"),
                        cacheRead = cached,
                    )
            }

    companion object {
        const val LOGIN_HINT = "Run `codex login` with your own account."
        private const val SCHEMA_FILE = "schema.json"
        private const val LAST_MESSAGE_FILE = "last-message.txt"
        private val FAILURE_TYPES = setOf("error", "turn.failed")
    }
}
