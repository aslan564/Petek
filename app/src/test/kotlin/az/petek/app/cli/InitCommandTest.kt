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

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InitCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `init prepares the working directory without loading a configuration`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, mapOf("PETEK_TARGET" to ""))

            val result = cli.run("init", "--target", "https://staging.example.com", "--ai", "agents")

            result.statusCode shouldBe 0
            result.stdout shouldContain "for agents (requested)"
            result.stdout shouldContain "created   .env — PETEK_TARGET=https://staging.example.com"
            result.stdout shouldContain "created   AGENTS.md"
            result.stdout shouldContain "created   .mcp.json — server \"petek\""
            result.stdout shouldContain "petek doctor"
            Files.readString(dir.resolve(".env")) shouldContain "PETEK_TARGET=https://staging.example.com"
            Files.exists(dir.resolve("AGENTS.md")) shouldBe true
        }

    @Test
    fun `--json prints the changes as one document and nothing else`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("--json", "init", "--ai", "codex")

            result.statusCode shouldBe 0
            val document = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            document["ais"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("agents")
            document["detected"]!!.jsonPrimitive.boolean shouldBe false
            document["changes"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content } shouldContain "AGENTS.md"
            result.stdout.lines().count { it.isNotBlank() } shouldBe 1
        }

    @Test
    fun `init can point at another directory and refuses an unknown agent`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val other = dir.resolve("site")

            cli.run("init", "--dir", "site", "--ai", "codex").statusCode shouldBe 0
            Files.exists(other.resolve("AGENTS.md")) shouldBe true
            Files.exists(other.resolve(".petek/petek.yaml")) shouldBe true

            val refused = cli.run("init", "--ai", "chatgpt")
            refused.statusCode shouldNotBe 0
            refused.stderr shouldContain "unknown AI 'chatgpt'"
        }
}
