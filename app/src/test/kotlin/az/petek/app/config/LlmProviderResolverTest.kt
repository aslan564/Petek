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
        marker("AGENTS.md")

        resolve(setOf("codex"), mapOf("ANTHROPIC_API_KEY" to "k"), explicit = LlmProviderKey.GEMINI_CLI) shouldBe
            LlmProviderResolver.Resolution(LlmProviderKey.GEMINI_CLI, "set in PETEK_LLM_PROVIDER")
    }

    @Test
    fun `settings and keys in the environment come before the project's markers`() {
        marker("AGENTS.md")

        resolve(setOf("codex"), mapOf("PETEK_LLM_BASE_URL" to "http://localhost:11434/v1")).provider shouldBe LlmProviderKey.OPENAI_COMPAT
        resolve(setOf("codex"), mapOf("PETEK_LLM_BIN" to "any-ai")).provider shouldBe LlmProviderKey.CLI
        resolve(setOf("codex"), mapOf("ANTHROPIC_API_KEY" to "k")).provider shouldBe LlmProviderKey.ANTHROPIC_API
        resolve(values = mapOf("GEMINI_API_KEY" to "k")) shouldBe
            LlmProviderResolver.Resolution(
                LlmProviderKey.OPENAI_COMPAT,
                "auto: GEMINI_API_KEY is set",
                LlmProviderResolver.GEMINI,
                "GEMINI_API_KEY",
            )
        resolve(setOf("gemini"), mapOf("GEMINI_API_KEY" to "k")).provider shouldBe LlmProviderKey.GEMINI_CLI
    }

    @Test
    fun `Grok and OpenRouter keys reach their OpenAI-compatible endpoints`() {
        resolve(values = mapOf("XAI_API_KEY" to "k")) shouldBe
            LlmProviderResolver.Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: XAI_API_KEY is set", LlmProviderResolver.XAI, "XAI_API_KEY")
        resolve(values = mapOf("OPENROUTER_API_KEY" to "k")).baseUrl shouldBe LlmProviderResolver.OPENROUTER
    }

    @Test
    fun `each project marker picks its own CLI when it is installed, and the other installed CLIs are fallbacks`() {
        listOf(
            "AGENTS.md" to LlmProviderKey.CODEX_CLI,
            ".codex" to LlmProviderKey.CODEX_CLI,
            "GEMINI.md" to LlmProviderKey.GEMINI_CLI,
            ".gemini" to LlmProviderKey.GEMINI_CLI,
        ).forEach { (name, provider) ->
            dir.toFile().listFiles()?.forEach { it.deleteRecursively() }
            marker(name)
            val other = if (provider == LlmProviderKey.CODEX_CLI) LlmProviderKey.GEMINI_CLI else LlmProviderKey.CODEX_CLI

            resolve(setOf("codex", "gemini")) shouldBe
                LlmProviderResolver.Resolution(provider, "auto: $name found", fallbacks = listOf(other))
        }
    }

    @Test
    fun `every other known agent CLI on PATH is a fallback, in order`() {
        marker("AGENTS.md")

        resolve(setOf("codex", "gemini", "opencode")).fallbacks shouldBe listOf(LlmProviderKey.GEMINI_CLI, LlmProviderKey.OPENCODE_CLI)
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
    fun `a local Ollama is found last, and nothing at all is no provider with the reason, never a vendor picked for you`() {
        resolve(setOf("ollama")) shouldBe
            LlmProviderResolver.Resolution(LlmProviderKey.OPENAI_COMPAT, "auto: ollama is on PATH", URI("http://localhost:11434/v1"))
        resolve() shouldBe
            LlmProviderResolver.Resolution(
                LlmProviderKey.NONE,
                "auto: no AI provider found (no AI setting or API key, no project AI marker, no known AI CLI on PATH)",
            )
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
