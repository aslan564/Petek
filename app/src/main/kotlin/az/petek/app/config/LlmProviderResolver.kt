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

package az.petek.app.config

import az.petek.llm.domain.LlmProviderKey
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides which AI provider answers when `PETEK_LLM_PROVIDER` is `auto` (the default). Pətək is tied to no vendor: it
 * uses whatever AI the machine and the project already have, in this order:
 *
 * 1. an explicit provider in `.env` or the environment;
 * 2. settings in the environment: `PETEK_LLM_BASE_URL` → `openai-compat`; `PETEK_LLM_BIN` → `cli` (any AI command-line
 *    tool, described by `PETEK_LLM_ARGS`); an API key: `ANTHROPIC_API_KEY` → `anthropic-api`; `OPENAI_API_KEY`,
 *    `XAI_API_KEY` (Grok), `OPENROUTER_API_KEY` → `openai-compat` at that service; `GEMINI_API_KEY` → `gemini-cli` when
 *    it is installed, else `openai-compat` at Gemini's OpenAI endpoint;
 * 3. the project's own AI, by the marker files in [projectDirectory] (read as markers only, never executed):
 *    `AGENTS.md`/`.codex/` → `codex-cli`, `GEMINI.md`/`.gemini/` → `gemini-cli`, each only when its CLI is installed;
 *    `.github/copilot-instructions.md` needs an OpenAI-compatible endpoint and says so;
 * 4. the known agent CLIs on `PATH` (`codex`, `gemini`, `opencode`), then `ollama` (a local OpenAI-compatible server).
 *
 * The other known agent CLIs found on `PATH` become [Resolution.fallbacks]: when the chosen one turns out to be
 * unavailable (not logged in, refused arguments), the next one answers. Nothing found is the `none` provider, whose
 * every call says how to set one up; `petek doctor` shows it. Every decision carries its reason.
 */
class LlmProviderResolver(
    private val projectDirectory: Path,
    private val onPath: (String) -> Boolean,
) {
    /**
     * The provider and why; [baseUrl] is the endpoint the decision implies (OpenAI, x.ai, OpenRouter, Gemini, Ollama),
     * [apiKeyVariable] the variable whose key it went by, [fallbacks] the other agent CLIs to try in order.
     */
    data class Resolution(
        val provider: LlmProviderKey,
        val reason: String,
        val baseUrl: URI? = null,
        val apiKeyVariable: String? = null,
        val fallbacks: List<LlmProviderKey> = emptyList(),
    )

    fun resolve(
        explicit: LlmProviderKey?,
        values: Map<String, String>,
    ): Resolution {
        if (explicit != null) return Resolution(explicit, "set in ${ConfigLoader.Keys.LLM_PROVIDER}")
        fromSettings(values)?.let { return it.withFallbacks() }
        val notes = mutableListOf<String>()
        fromMarkers(notes)?.let { return it.withFallbacks() }
        fromPath()?.let { found ->
            return found.copy(reason = AUTO + (notes + found.reason.removePrefix(AUTO)).joinToString("; ")).withFallbacks()
        }
        val why = (notes + "no AI provider found (no AI setting or API key, no project AI marker, no known AI CLI on PATH)")
        return Resolution(LlmProviderKey.NONE, AUTO + why.joinToString("; "))
    }

    private fun Resolution.withFallbacks(): Resolution =
        copy(fallbacks = BINARIES.filter { (provider, binary) -> provider != this.provider && onPath(binary) }.keys.toList())

    private fun fromSettings(values: Map<String, String>): Resolution? {
        fun set(key: String) = !values[key].isNullOrBlank()

        fun key(
            variable: String,
            at: URI,
        ) = Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: $variable is set", at, variable)
        return when {
            set(ConfigLoader.Keys.LLM_BASE_URL) -> {
                Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ${ConfigLoader.Keys.LLM_BASE_URL} is set")
            }

            set(ConfigLoader.Keys.LLM_BIN) -> {
                Resolution(LlmProviderKey.CLI, "auto: ${ConfigLoader.Keys.LLM_BIN} is set")
            }

            set(ConfigLoader.Keys.ANTHROPIC_API_KEY) -> {
                Resolution(
                    LlmProviderKey.ANTHROPIC_API,
                    "auto: ${ConfigLoader.Keys.ANTHROPIC_API_KEY} is set",
                    apiKeyVariable = ConfigLoader.Keys.ANTHROPIC_API_KEY,
                )
            }

            set(ConfigLoader.Keys.OPENAI_API_KEY) -> {
                key(ConfigLoader.Keys.OPENAI_API_KEY, OPENAI)
            }

            set(ConfigLoader.Keys.XAI_API_KEY) -> {
                key(ConfigLoader.Keys.XAI_API_KEY, XAI)
            }

            set(ConfigLoader.Keys.OPENROUTER_API_KEY) -> {
                key(ConfigLoader.Keys.OPENROUTER_API_KEY, OPENROUTER)
            }

            set(ConfigLoader.Keys.GEMINI_API_KEY) && onPath("gemini") -> {
                Resolution(LlmProviderKey.GEMINI_CLI, "auto: ${ConfigLoader.Keys.GEMINI_API_KEY} is set and gemini is installed")
            }

            set(ConfigLoader.Keys.GEMINI_API_KEY) -> {
                key(ConfigLoader.Keys.GEMINI_API_KEY, GEMINI)
            }

            else -> {
                null
            }
        }
    }

    private fun fromMarkers(notes: MutableList<String>): Resolution? {
        for ((markers, provider) in MARKERS) {
            val found = markers.firstOrNull { Files.exists(projectDirectory.resolve(it)) } ?: continue
            val binary = BINARIES.getValue(provider)
            if (onPath(binary)) return Resolution(provider, "auto: $found found")
            notes += "$found found, but $binary is not on PATH"
        }
        if (Files.exists(projectDirectory.resolve(COPILOT_MARKER))) {
            notes += "$COPILOT_MARKER found: Copilot has no headless CLI; set ${ConfigLoader.Keys.LLM_BASE_URL} to an " +
                "OpenAI-compatible endpoint"
        }
        return null
    }

    private fun fromPath(): Resolution? {
        BINARIES.entries.firstOrNull { (_, binary) -> onPath(binary) }?.let { (provider, binary) ->
            return Resolution(provider, "auto: $binary is on PATH")
        }
        if (onPath("ollama")) return Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ollama is on PATH", OLLAMA)
        return null
    }

    companion object {
        private const val AUTO = "auto: "
        val OPENAI: URI = URI("https://api.openai.com/v1")
        val XAI: URI = URI("https://api.x.ai/v1")
        val OPENROUTER: URI = URI("https://openrouter.ai/api/v1")
        val GEMINI: URI = URI("https://generativelanguage.googleapis.com/v1beta/openai")
        val OLLAMA: URI = URI("http://localhost:11434/v1")
        private const val COPILOT_MARKER = ".github/copilot-instructions.md"

        private val MARKERS: List<Pair<List<String>, LlmProviderKey>> =
            listOf(
                listOf("AGENTS.md", ".codex") to LlmProviderKey.CODEX_CLI,
                listOf("GEMINI.md", ".gemini") to LlmProviderKey.GEMINI_CLI,
            )

        private val BINARIES: Map<LlmProviderKey, String> = PetekConfig.DEFAULT_BINARIES

        /** Looks [binary] up on the `PATH` of [environment] (with `PATHEXT` on Windows); absolute names are checked as given. */
        fun pathLookup(environment: Map<String, String>): (String) -> Boolean =
            { binary ->
                val direct = File(binary)
                if (direct.isAbsolute) {
                    direct.canExecute()
                } else {
                    val extensions = listOf("") + environment["PATHEXT"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
                    environment["PATH"]
                        .orEmpty()
                        .split(File.pathSeparatorChar)
                        .filter { it.isNotBlank() }
                        .any { dir -> extensions.any { ext -> File(dir, binary + ext).let { it.isFile && it.canExecute() } } }
                }
            }
    }
}
