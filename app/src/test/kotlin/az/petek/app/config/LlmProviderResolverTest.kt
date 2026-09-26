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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class LlmProviderResolverTest {
    @TempDir
    lateinit var dir: Path

    private fun resolve(
        installed: Set<String> = emptySet(),
        values: Map<String, String> = emptyMap(),
        explicit: LlmProviderKey? = null,
    ) = LlmProviderResolver(dir, { it in installed }).resolve(explicit, values)

    private fun marker(name: String) {
        val path = dir.resolve(name)
        Files.createDirectories(path.parent)
        if (name.endsWith(".md")) Files.writeString(path, "# instructions") else Files.createDirectories(path)
    }

    @Test
    fun `an explicit provider wins over everything`() {
        marker("CLAUDE.md")

        resolve(setOf("claude"), mapOf("ANTHROPIC_API_KEY" to "k"), explicit = LlmProviderKey.GEMINI_CLI) shouldBe
            LlmProviderResolver.Resolution(LlmProviderKey.GEMINI_CLI, "set in PETEK_LLM_PROVIDER")
    }

    @Test
    fun `keys in the environment come before the project's markers`() {
        marker("CLAUDE.md")

        resolve(setOf("claude"), mapOf("PETEK_LLM_BASE_URL" to "http://localhost:11434/v1")).provider shouldBe LlmProviderKey.OPENAI_COMPAT
        resolve(setOf("claude"), mapOf("ANTHROPIC_API_KEY" to "k")).provider shouldBe LlmProviderKey.ANTHROPIC_API
        resolve(values = mapOf("GEMINI_API_KEY" to "k")) shouldBe
            LlmProviderResolver.Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: GEMINI_API_KEY is set", LlmProviderResolver.GEMINI)
        resolve(setOf("gemini"), mapOf("GEMINI_API_KEY" to "k")).provider shouldBe LlmProviderKey.GEMINI_CLI
    }

    @Test
    fun `each project marker picks its own CLI when it is installed`() {
        listOf(
            "CLAUDE.md" to LlmProviderKey.CLAUDE_CLI,
            ".claude" to LlmProviderKey.CLAUDE_CLI,
            "AGENTS.md" to LlmProviderKey.CODEX_CLI,
            ".codex" to LlmProviderKey.CODEX_CLI,
            "GEMINI.md" to LlmProviderKey.GEMINI_CLI,
            ".gemini" to LlmProviderKey.GEMINI_CLI,
        ).forEach { (name, provider) ->
            dir.toFile().listFiles()?.forEach { it.deleteRecursively() }
            marker(name)

            resolve(setOf("claude", "codex", "gemini")) shouldBe LlmProviderResolver.Resolution(provider, "auto: $name found")
        }
    }

    @Test
    fun `copilot's marker asks for an OpenAI-compatible endpoint and the search goes on`() {
        marker(".github/copilot-instructions.md")

        val resolution = resolve(setOf("opencode"))

        resolution.provider shouldBe LlmProviderKey.OPENCODE_CLI
        resolution.reason shouldContain "Copilot has no headless CLI; set PETEK_LLM_BASE_URL"
        resolution.reason shouldContain "; opencode is on PATH"
    }

    @Test
    fun `a local Ollama is found last, and nothing at all falls back to claude-cli with the reason`() {
        resolve(setOf("ollama")) shouldBe
            LlmProviderResolver.Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ollama is on PATH", URI("http://localhost:11434/v1"))
        resolve().reason shouldBe
            "auto: no AI provider found (no API key, no project AI marker, no known CLI on PATH); defaulting to claude-cli"
    }

    @Test
    fun `the PATH lookup finds executables in the listed directories only`() {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val tool = Files.writeString(bin.resolve("codex"), "#!/bin/sh\n")
        tool.toFile().setExecutable(true)
        val lookup = LlmProviderResolver.pathLookup(mapOf("PATH" to bin.toString()))

        lookup("codex") shouldBe true
        lookup("gemini") shouldBe false
        lookup(tool.toString()) shouldBe true
    }
}
