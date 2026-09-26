/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import az.petek.llm.domain.LlmProviderKey
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides which AI provider answers when `PETEK_LLM_PROVIDER` is `auto` (the default), in this order:
 *
 * 1. an explicit provider in `.env` or the environment;
 * 2. keys in the environment: `PETEK_LLM_BASE_URL` → `openai-compat`; `ANTHROPIC_API_KEY` → `anthropic-api`;
 *    `OPENAI_API_KEY` → `openai-compat` at OpenAI; `GEMINI_API_KEY` → `gemini-cli` when it is installed, else
 *    `openai-compat` at Gemini's OpenAI endpoint;
 * 3. the project's own AI, by the marker files in [projectDirectory] (read as markers only, never executed):
 *    `CLAUDE.md`/`.claude/` → `claude-cli`, `AGENTS.md`/`.codex/` → `codex-cli`, `GEMINI.md`/`.gemini/` →
 *    `gemini-cli`, each only when its CLI is installed; `.github/copilot-instructions.md` needs an OpenAI-compatible
 *    endpoint and says so;
 * 4. the binaries on `PATH`: `claude`, `codex`, `gemini`, `opencode`, then `ollama` (a local OpenAI-compatible server).
 *
 * Nothing found falls back to `claude-cli`, and `petek doctor` then says how to log in. Every decision carries its
 * reason, which the config keeps and `doctor` shows.
 */
class LlmProviderResolver(
    private val projectDirectory: Path,
    private val onPath: (String) -> Boolean,
) {
    /** The provider and why; [baseUrl] is the endpoint the decision implies (OpenAI, Gemini, Ollama), if any. */
    data class Resolution(
        val provider: LlmProviderKey,
        val reason: String,
        val baseUrl: URI? = null,
    )

    fun resolve(
        explicit: LlmProviderKey?,
        values: Map<String, String>,
    ): Resolution {
        if (explicit != null) return Resolution(explicit, "set in ${ConfigLoader.Keys.LLM_PROVIDER}")
        fromKeys(values)?.let { return it }
        val notes = mutableListOf<String>()
        fromMarkers(notes)?.let { return it }
        fromPath()?.let { found -> return found.copy(reason = AUTO + (notes + found.reason.removePrefix(AUTO)).joinToString("; ")) }
        val why = (notes + "no AI provider found (no API key, no project AI marker, no known CLI on PATH)").joinToString("; ")
        return Resolution(LlmProviderKey.CLAUDE_CLI, "auto: $why; defaulting to claude-cli")
    }

    private fun fromKeys(values: Map<String, String>): Resolution? {
        fun set(key: String) = !values[key].isNullOrBlank()
        return when {
            set(ConfigLoader.Keys.LLM_BASE_URL) -> {
                Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ${ConfigLoader.Keys.LLM_BASE_URL} is set")
            }

            set(ConfigLoader.Keys.ANTHROPIC_API_KEY) -> {
                Resolution(LlmProviderKey.ANTHROPIC_API, "auto: ${ConfigLoader.Keys.ANTHROPIC_API_KEY} is set")
            }

            set(ConfigLoader.Keys.OPENAI_API_KEY) -> {
                Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ${ConfigLoader.Keys.OPENAI_API_KEY} is set", OPENAI)
            }

            set(ConfigLoader.Keys.GEMINI_API_KEY) && onPath("gemini") -> {
                Resolution(LlmProviderKey.GEMINI_CLI, "auto: ${ConfigLoader.Keys.GEMINI_API_KEY} is set and gemini is installed")
            }

            set(ConfigLoader.Keys.GEMINI_API_KEY) -> {
                Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ${ConfigLoader.Keys.GEMINI_API_KEY} is set", GEMINI)
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
        val GEMINI: URI = URI("https://generativelanguage.googleapis.com/v1beta/openai")
        val OLLAMA: URI = URI("http://localhost:11434/v1")
        private const val COPILOT_MARKER = ".github/copilot-instructions.md"

        private val MARKERS: List<Pair<List<String>, LlmProviderKey>> =
            listOf(
                listOf("CLAUDE.md", ".claude") to LlmProviderKey.CLAUDE_CLI,
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
