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
import az.petek.app.testing.PanelLlm
import az.petek.app.testing.PanelWaits
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.TestFlowView
import az.petek.dashboard.domain.TestStage
import az.petek.evidence.domain.RunResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** `petek test`: the main path from the command line, with production wiring, a scripted site and a scripted LLM. */
class TestCommandTest {
    @TempDir
    lateinit var dir: Path

    private fun cli(): CliHarness = CliHarness(dir, llm = PanelLlm().client).apply { explorerBrowser = PanelWaits.site() }

    @Test
    fun `the site is explored, the draft approved and run in one go, and the exit code is the run's result`() =
        runBlocking<Unit> {
            val cli = cli()

            val result = cli.run("test", "--testers", "2", "--max-pages", "5")

            result.stdout shouldContain "Testing ${CliHarness.UNUSED_TARGET} with 2 tester(s)"
            result.stdout shouldContain "Exploration exp_"
            result.stdout shouldContain "drafted from what the explorer found"
            result.stdout shouldContain "Test bitdi"
            result.stdout shouldContain "Report: "
            val run = cli.evidence { it.evidence.list(10).single() }
            val expected = if (run.result == RunResult.PASSED) ExitCodes.OK else ExitCodes.FAILURE
            result.statusCode shouldBe expected
            result.stdout shouldContain "Run ${run.runId.value} is going with 2 tester(s)"
        }

    @Test
    fun `--json prints the test's end as one document with its report`() =
        runBlocking<Unit> {
            val cli = cli()

            val result = cli.run("--json", "test", "--testers", "2", "--max-pages", "5")

            val document = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            document["stage"]!!.jsonPrimitive.content shouldBe "FINISHED"
            document["exitCode"]!!.jsonPrimitive.int shouldBe result.statusCode
            val runId = RunId(document["runId"]!!.jsonPrimitive.content)
            val report = Path.of(document["report"].shouldNotBeNull().jsonPrimitive.content)
            Files.isRegularFile(report) shouldBe true
            report.toString() shouldContain runId.value
        }

    @Test
    fun `a test whose run did not pass only because checks could not be decided exits with 3`() {
        val failed = TestFlowView("http://site.test", TestStage.FINISHED, Instant.EPOCH, result = RunResult.FAILED)

        TestCommand.exitCodeOf(failed.copy(undecided = true)) shouldBe ExitCodes.INCONCLUSIVE
        TestCommand.exitCodeOf(failed) shouldBe ExitCodes.FAILURE
        TestCommand.exitCodeOf(failed.copy(result = RunResult.PASSED)) shouldBe ExitCodes.OK
    }

    @Test
    fun `without a site nothing starts and the owner is asked for one`() =
        runBlocking<Unit> {
            val cli = cli().apply { env.remove("PETEK_TARGET") }

            val result = cli.run("test")

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain "No site to test was given"
            Files.exists(cli.evidenceDir) shouldBe false
        }

    @Test
    fun `a production host is refused before anything starts`() =
        runBlocking<Unit> {
            val cli = cli()

            val result = cli.run("test", "--target", "https://portal.example")

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain "Refusing to contact the target"
        }
}
