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

package az.petek.app.init

import az.petek.app.init.ProjectInitializer.Outcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class ProjectInitializerTest {
    @TempDir
    lateinit var project: Path

    private val initializer = ProjectInitializer()

    private fun read(relative: String): String = Files.readString(project.resolve(relative))

    private fun write(
        relative: String,
        text: String,
    ) {
        val file = project.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `an empty project gets the configuration, the profile, the skill pack and the two default agents`() {
        val result = initializer.initialize(ProjectInitializer.Request(project, URI("https://staging.example.com")))

        result.detected shouldBe true
        result.ais shouldBe ProjectInitializer.DEFAULT_AIS
        result.changes.map { it.path to it.outcome } shouldContainExactly
            listOf(
                ".env" to Outcome.CREATED,
                ".petek/petek.yaml" to Outcome.CREATED,
                ".petek/SKILL.md" to Outcome.CREATED,
                ".gitignore" to Outcome.CREATED,
                ".claude/skills/petek/SKILL.md" to Outcome.CREATED,
                "CLAUDE.md" to Outcome.CREATED,
                ".mcp.json" to Outcome.CREATED,
                "AGENTS.md" to Outcome.CREATED,
            )
        read(".env") shouldContain "PETEK_TARGET=https://staging.example.com\n"
        read(".env") shouldContain "PETEK_TEST_TOKEN=\n"
        read(".petek/petek.yaml") shouldContain "target: https://staging.example.com\n"
        read(".petek/SKILL.md") shouldStartWith "---\nname: petek\n"
        read(".claude/skills/petek/SKILL.md") shouldBe read(".petek/SKILL.md")
        read("CLAUDE.md") shouldStartWith ProjectInitializer.BEGIN
        read("CLAUDE.md") shouldContain "read `.petek/SKILL.md`"
        read(".gitignore") shouldContain "\n.env\n"
        read(".gitignore") shouldContain "\nevidence/\n"
        val mcp =
            Json
                .parseToJsonElement(read(".mcp.json"))
                .jsonObject["mcpServers"]!!
                .jsonObject["petek"]!!
                .jsonObject
        mcp["command"]!!.jsonPrimitive.content shouldBe "petek"
    }

    @Test
    fun `the owner's instruction files keep their text and gain the fragment once, refreshed on a re-run`() {
        write("CLAUDE.md", "# My project\n\nRun the tests with npm test.\n")
        write(".gitignore", "node_modules/\n.env\n")

        initializer.initialize(ProjectInitializer.Request(project, ais = setOf(HostAi.CLAUDE)))
        val first = read("CLAUDE.md")
        first shouldStartWith "# My project\n\nRun the tests with npm test.\n\n${ProjectInitializer.BEGIN}"
        first.lines().count { it == ProjectInitializer.BEGIN } shouldBe 1
        read(".gitignore") shouldBe "node_modules/\n.env\n\n# Pətək: configuration with secrets, evidence of runs\nevidence/\n"

        // A stale fragment is replaced in place; nothing else moves.
        write("CLAUDE.md", first.replace("Pətək runs next to this project", "OLD TEXT") + "\nMore of my own notes.\n")
        val again = initializer.initialize(ProjectInitializer.Request(project, ais = setOf(HostAi.CLAUDE)))

        again.changes.single { it.path == "CLAUDE.md" }.outcome shouldBe Outcome.UPDATED
        read("CLAUDE.md") shouldNotContain "OLD TEXT"
        read("CLAUDE.md") shouldContain "\nMore of my own notes.\n"
        read("CLAUDE.md").lines().count { it == ProjectInitializer.BEGIN } shouldBe 1
        again.changes.single { it.path == ".gitignore" }.outcome shouldBe Outcome.UNCHANGED

        val third = initializer.initialize(ProjectInitializer.Request(project, ais = setOf(HostAi.CLAUDE)))
        third.written shouldBe emptyList()
    }

    @Test
    fun `an existing env file and a changed profile are kept, the profile is rewritten only with force`() {
        write(".env", "PETEK_TARGET=https://mine.example.com\nPETEK_TEST_TOKEN=secret\n")
        write(".petek/petek.yaml", "target: https://mine.example.com\n")

        val result = initializer.initialize(ProjectInitializer.Request(project, URI("https://other.example.com"), setOf(HostAi.CODEX)))

        result.changes.single { it.path == ".env" }.outcome shouldBe Outcome.KEPT
        read(".env") shouldBe "PETEK_TARGET=https://mine.example.com\nPETEK_TEST_TOKEN=secret\n"
        result.changes.single { it.path == ".petek/petek.yaml" }.outcome shouldBe Outcome.KEPT

        val forced =
            initializer.initialize(
                ProjectInitializer.Request(project, URI("https://other.example.com"), setOf(HostAi.CODEX), force = true),
            )

        forced.changes.single { it.path == ".petek/petek.yaml" }.outcome shouldBe Outcome.UPDATED
        read(".petek/petek.yaml") shouldContain "target: https://other.example.com"
        forced.changes.single { it.path == ".env" }.outcome shouldBe Outcome.KEPT
    }

    @Test
    fun `the agents are detected from the project's markers`() {
        write(".cursor/rules/style.mdc", "---\ndescription: style\n---\nUse tabs.\n")
        write("GEMINI.md", "# Gemini notes\n")

        val result = initializer.initialize(ProjectInitializer.Request(project))

        result.ais shouldContainExactlyInAnyOrder setOf(HostAi.CURSOR, HostAi.GEMINI)
        read(".cursor/rules/petek.mdc") shouldStartWith "${ProjectInitializer.BEGIN}\n---\ndescription: Pətək"
        read("GEMINI.md") shouldStartWith "# Gemini notes\n\n${ProjectInitializer.BEGIN}"
        Files.exists(project.resolve(".cursor/mcp.json")) shouldBe true
        Files.exists(project.resolve(".gemini/settings.json")) shouldBe true
        Files.exists(project.resolve("CLAUDE.md")) shouldBe false
    }

    @Test
    fun `an MCP configuration keeps its other servers and keys, and Copilot's file uses its own shape`() {
        write(".mcp.json", """{"mcpServers": {"github": {"command": "gh-mcp"}}, "other": 1}""")
        write(".vscode/mcp.json", """{"servers": {"fs": {"type": "stdio", "command": "fs"}}}""")

        initializer.initialize(ProjectInitializer.Request(project, ais = setOf(HostAi.CLAUDE, HostAi.COPILOT)))

        val claude = Json.parseToJsonElement(read(".mcp.json")).jsonObject
        claude["other"]!!.jsonPrimitive.content shouldBe "1"
        claude["mcpServers"]!!.jsonObject.keys shouldContainExactlyInAnyOrder setOf("github", "petek")
        val copilot = Json.parseToJsonElement(read(".vscode/mcp.json")).jsonObject["servers"]!!.jsonObject
        copilot.keys shouldContainExactlyInAnyOrder setOf("fs", "petek")
        read(".github/copilot-instructions.md") shouldContain "petek doctor"

        val again = initializer.initialize(ProjectInitializer.Request(project, ais = setOf(HostAi.CLAUDE, HostAi.COPILOT)))
        again.changes.single { it.path == ".mcp.json" }.outcome shouldBe Outcome.UNCHANGED
    }

    @Test
    fun `a broken MCP file is left alone and reported`() {
        write(".mcp.json", "{not json")

        val result = initializer.initialize(ProjectInitializer.Request(project, ais = setOf(HostAi.CLAUDE)))

        result.changes.single { it.path == ".mcp.json" }.outcome shouldBe Outcome.KEPT
        read(".mcp.json") shouldBe "{not json"
    }

    @Test
    fun `the ai list is parsed with all and rejects unknown names`() {
        HostAi.parse("claude, cursor") shouldContainExactly setOf(HostAi.CLAUDE, HostAi.CURSOR)
        HostAi.parse("all") shouldContainExactly HostAi.entries.toSet()
        shouldThrow<IllegalArgumentException> { HostAi.parse("copilot,chatgpt") }.message shouldContain "unknown AI 'chatgpt'"
    }
}
