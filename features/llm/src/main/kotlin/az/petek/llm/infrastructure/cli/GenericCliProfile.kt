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
import az.petek.llm.infrastructure.StrictSchema
import az.petek.llm.infrastructure.StructuredJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Any command-line AI agent, described entirely by the owner's configuration instead of by Pətək's code, so Pətək is
 * tied to no vendor: the executable ([CliAgentConfig.executable], `PETEK_LLM_BIN`) and an argument template
 * ([CliAgentConfig.arguments], `PETEK_LLM_ARGS`) with these placeholders, filled per call:
 *
 * - `{model}` (`PETEK_LLM_MODEL`) and `{effort}` (`PETEK_LLM_EFFORT`): a word that is only the placeholder is left out
 *   when the value is not set, together with the option right before it (`--model {model}` disappears);
 * - `{system}`: the system text with the JSON Schema demand; without it that text leads the STDIN transcript;
 * - `{schema}` and `{schema_file}`: the requested JSON Schema, inline or as a file, for tools with native structured
 *   output.
 *
 * The conversation goes to STDIN. The answer is read from STDOUT: the JSON object itself, or a result envelope of the
 * kind agent CLIs print in a JSON output mode (`structured_output`, or `result`/`response`/`output`/`text` holding the
 * answer, `is_error`, `usage`, `total_cost_usd`); an answer counts only when it carries the schema's required fields.
 * Environment variables named in [CliAgentConfig.unsetEnvironment] (`NAME` or `PREFIX*`) are removed for the child, e.g.
 * the markers an enclosing agent session sets.
 */
internal class GenericCliProfile(
    private val config: CliAgentConfig,
) : CliAgentProfile {
    override val provider: LlmProviderKey = LlmProviderKey.CLI
    override val model: String = config.modelLabel
    override val displayName: String = "AI command-line tool (${config.executable})"
    override val scratchPrefix: String = "petek-cli-"

    override fun call(
        request: LlmRequest,
        scratch: Path,
    ): CliCall {
        val system = SchemaPrompt.system(request.system, request.responseSchema)
        val schemaText = request.responseSchema.toString()
        Files.writeString(scratch.resolve(REQUIRED_FILE), required(request.responseSchema).joinToString("\n"))
        val schemaFile by lazy { Files.writeString(scratch.resolve(SCHEMA_FILE), StrictSchema.of(request.responseSchema).toString()) }
        val usesSystem = config.arguments.any { SYSTEM in it }
        val values: Map<String, () -> String?> =
            mapOf(
                MODEL to { config.model },
                EFFORT to { config.effort },
                SYSTEM to { system },
                SCHEMA to { schemaText },
                SCHEMA_FILE_PLACEHOLDER to { schemaFile.toString() },
            )
        val arguments = mutableListOf<String>()
        for (word in config.arguments) {
            val placeholder = values.keys.firstOrNull { word == it }
            if (placeholder != null && values.getValue(placeholder)() == null) {
                if (arguments.lastOrNull()?.startsWith("-") == true) arguments.removeAt(arguments.lastIndex)
                continue
            }
            arguments += values.entries.fold(word) { text, (key, value) -> if (key in text) text.replace(key, value().orEmpty()) else text }
        }
        val stdin = if (usesSystem) CliTranscripts.of(request.messages) else CliTranscripts.withSystem(system, request.messages)
        return CliCall(listOf(config.executable) + arguments, stdin)
    }

    override fun environment(inherited: Map<String, String>): Map<String, String> =
        inherited.filterKeys { name ->
            config.unsetEnvironment.none { pattern ->
                if (pattern.endsWith("*")) name.startsWith(pattern.dropLast(1)) else name == pattern
            }
        }

    override fun parse(
        output: ProcessOutput,
        scratch: Path,
        environment: Map<String, String>,
        label: String,
    ): LlmResponse {
        val required = Files.readAllLines(scratch.resolve(REQUIRED_FILE)).filter { it.isNotBlank() }.toSet()
        val envelope = StructuredJson.parseObject(output.stdout) ?: CliOutputs.jsonLines(output.stdout).lastOrNull()
        if (envelope != null && !answers(envelope, required) && isError(envelope)) {
            throw CliOutputs.failure(displayName, LOGIN_HINT, errorText(envelope), label, usageHint())
        }
        val answer = envelope?.let { answerIn(it, required) }
        if (answer == null) {
            if (output.exitCode != 0) {
                throw CliOutputs.failure(displayName, LOGIN_HINT, output.stderr.ifBlank { output.stdout }, label, usageHint())
            }
            throw LlmException.InvalidOutput(
                "$displayName answer for $label is not a JSON object with ${required.joinToString()}",
                output.stdout,
            )
        }
        return LlmResponse(StrictSchema.withoutNulls(answer), usage(envelope), model, costUsd = cost(envelope))
    }

    private fun usageHint() = "check the arguments in PETEK_LLM_ARGS with `${config.executable} --help`"

    private fun answers(
        json: JsonObject,
        required: Set<String>,
    ): Boolean = required.all { it in json.keys }

    /** The object itself, or the answer inside a result envelope. */
    private fun answerIn(
        envelope: JsonObject,
        required: Set<String>,
    ): JsonObject? {
        if (answers(envelope, required)) return envelope
        (envelope[STRUCTURED] as? JsonObject)?.takeIf { answers(it, required) }?.let { return it }
        return TEXT_KEYS
            .asSequence()
            .mapNotNull { (envelope[it] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            .mapNotNull { StructuredJson.parseObject(it) }
            .firstOrNull { answers(it, required) }
    }

    private fun isError(envelope: JsonObject): Boolean =
        (envelope["is_error"] as? JsonPrimitive)?.booleanOrNull == true || envelope["error"] != null

    private fun errorText(envelope: JsonObject): String =
        (envelope["error"] as? JsonObject)?.let { CliOutputs.string(it, "message") }
            ?: (envelope["error"] as? JsonPrimitive)?.contentOrNull
            ?: TEXT_KEYS.firstNotNullOfOrNull { CliOutputs.string(envelope, it)?.takeIf(String::isNotBlank) }
            ?: envelope.toString()

    private fun usage(envelope: JsonObject?): TokenUsage {
        val usage = CliOutputs.obj(envelope, "usage") ?: return TokenUsage()
        return CliOutputs.usage(
            input = CliOutputs.long(usage, "input_tokens"),
            output = CliOutputs.long(usage, "output_tokens"),
            cacheRead = CliOutputs.long(usage, "cache_read_input_tokens"),
        )
    }

    private fun cost(envelope: JsonObject?): Double? = (envelope?.get("total_cost_usd") as? JsonPrimitive)?.doubleOrNull

    private fun required(schema: JsonObject): List<String> =
        (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    companion object {
        const val LOGIN_HINT = "Log in to this AI tool with its own login command, then run `petek doctor` again."
        const val MODEL = "{model}"
        const val EFFORT = "{effort}"
        const val SYSTEM = "{system}"
        const val SCHEMA = "{schema}"
        const val SCHEMA_FILE_PLACEHOLDER = "{schema_file}"
        private const val REQUIRED_FILE = "required.txt"
        private const val SCHEMA_FILE = "schema.json"
        private const val STRUCTURED = "structured_output"
        private val TEXT_KEYS = listOf("result", "response", "output", "text", "content")
    }
}
