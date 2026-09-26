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
import az.petek.app.testing.CliHarness.Companion.tinyCampaign
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** `run --ci`, `findings`, `dev` and `--json` on `capacity` (Faza 11 and 12). */
class CiAndToolCommandsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `run --ci names the JUnit XML and SARIF files and fills the GitHub job summary`() =
        runBlocking<Unit> {
            val summary = dir.resolve("step-summary.md")
            val cli = CliHarness(dir, environment = mapOf("GITHUB_STEP_SUMMARY" to summary.toString()))
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml", "--ci")

            result.statusCode shouldBe 0
            val junit = Regex("JUnit XML: (\\S+)").find(result.stdout)!!.groupValues[1]
            val sarif = Regex("SARIF: (\\S+)").find(result.stdout)!!.groupValues[1]
            Files.readString(Path.of(junit)) shouldContain "<testsuite name="
            Files.readString(Path.of(sarif)) shouldContain "\"version\": \"2.1.0\""
            Files.readString(summary) shouldContain "# "
        }

    @Test
    fun `findings of the latest run come as one JSON document for a coding AI`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign())
            cli.run("run", "tiny.yaml").statusCode shouldBe 0

            val result = cli.run("--json", "findings", "latest")

            result.statusCode shouldBe 0
            val document = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            document["runId"]!!.jsonPrimitive.content shouldContain "run_"
            document["findings"]!!.jsonArray.size shouldBe 0
        }

    @Test
    fun `dev waits for the app and gives up with the reason when it never answers`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            Files.writeString(dir.resolve(".env"), "PETEK_TARGET=${CliHarness.UNUSED_TARGET}\n")

            val result = cli.run("dev", "--wait", "1", "--no-open", "--health", "http://127.0.0.1:9/healthz")

            result.statusCode shouldBe 2
            result.stderr shouldContain "Tətbiq 1 saniyədə cavab vermədi: http://127.0.0.1:9/healthz"
        }

    @Test
    fun `capacity answers in JSON too`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("--json", "capacity")

            result.statusCode shouldBe 0
            val advice = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            (advice["maxTesters"]!!.jsonPrimitive.content.toInt() >= 1) shouldBe true
            advice["limitingFactor"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
        }
}
