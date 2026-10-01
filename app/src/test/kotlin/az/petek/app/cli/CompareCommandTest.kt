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
import az.petek.app.testing.FakeBrowserEngine
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

class CompareCommandTest {
    @TempDir
    lateinit var dir: Path

    /** The site greets its readers in one release and not in the next: the check of `look` passes, then fails. */
    private suspend fun twoReleases(cli: CliHarness): Pair<String, String> {
        cli.write("greet.yaml", GREETING)
        cli.browser = FakeBrowserEngine { session, _ -> session.visibleTexts += "Xoş gəldiniz" }
        cli.run("run", "greet.yaml", "--release", "v1.4.1").statusCode shouldBe 0
        val first =
            cli.evidence {
                it.evidence
                    .latest()!!
                    .runId.value
            }
        cli.browser = FakeBrowserEngine()
        cli.run("run", "greet.yaml", "--release", "v1.4.2").statusCode shouldBe ExitCodes.FAILURE
        val second =
            cli.evidence {
                it.evidence
                    .latest()!!
                    .runId.value
            }
        return first to second
    }

    @Test
    fun `a check the site passed in one release and fails in the next is a regression, with exit code 1`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val (first, second) = twoReleases(cli)

            val result = cli.run("compare", "latest")

            result.statusCode shouldBe ExitCodes.FAILURE
            result.stdout shouldContain "Comparing $second (release v1.4.2) with $first (release v1.4.1), scenario 'greet'."
            result.stdout shouldContain "Worse: yes"
            result.stdout shouldContain "New failures (1): look"
            val page =
                cli.evidenceDir
                    .resolve(second)
                    .resolve("report")
                    .resolve("compare-$first.html")
            result.stdout shouldContain "Written: $page"
            Files.readString(page) shouldContain "yeni sınıb"
        }

    @Test
    fun `a release is named as the baseline, and --json gives the whole comparison`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val (first, second) = twoReleases(cli)

            val result = cli.run("--json", "compare", second, "--baseline", "v1.4.1")

            val document = Json.parseToJsonElement(result.stdout.substring(result.stdout.indexOf('{'))).jsonObject
            document["baseline"]!!.jsonPrimitive.content shouldBe first
            document["baselineRelease"]!!.jsonPrimitive.content shouldBe "v1.4.1"
            document["regressed"]!!.jsonPrimitive.boolean shouldBe true
            document["steps"]!!
                .jsonArray
                .single { it.jsonObject["step"]!!.jsonPrimitive.content == "look" }
                .jsonObject["change"]!!
                .jsonPrimitive.content shouldBe "NEW_FAILURE"
        }

    @Test
    fun `a run with nothing earlier to compare with exits with 2 and says why`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("greet.yaml", GREETING)
            cli.run("run", "greet.yaml").statusCode

            val result = cli.run("compare", "latest")

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain "No earlier finished run of 'greet'"
        }

    @Test
    fun `an unknown run or an empty store compares nothing and exits with 2, never as a regression`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)

            cli.run("compare", "latest").let {
                it.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
                it.stderr shouldContain "No run is recorded"
            }
            cli.write("greet.yaml", GREETING)
            cli.run("run", "greet.yaml").statusCode
            cli.run("compare", "run_mistyped").let {
                it.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
                it.stderr shouldContain "run_mistyped"
            }
            cli.run("compare", "latest", "--baseline", "run_mistyped").statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
        }

    @Test
    fun `the visual gate is report or fail, and runs without page looks compare as before`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val (_, second) = twoReleases(cli)

            cli.run("compare", second, "--visual", "sideways").let {
                it.statusCode shouldNotBe ExitCodes.OK
                it.stderr shouldContain "--visual"
            }
            cli.run("--json", "compare", second, "--visual", "fail").let { result ->
                val document = Json.parseToJsonElement(result.stdout.substring(result.stdout.indexOf('{'))).jsonObject
                document["visualGate"]!!.jsonPrimitive.content shouldBe "fail"
                document["looks"]!!.jsonArray.size shouldBe 0
                // The regression comes from the step the site broke, not from a look.
                result.statusCode shouldBe ExitCodes.FAILURE
            }
        }

    private companion object {
        /** An owner signs up, an employee looks at the home page, which must greet them. */
        val GREETING =
            """
            campaign:
              name: greet
              testers: 2
              seed: 7
              roles: {admin: 1, manager: 0, employee: 1}
              departments: [IT]
              budget: {max_steps_per_agent: 5, max_minutes: 2}
            setup:
              - id: signup
                actor: admin
                do: "Sign up and create the company"
            steps:
              - id: look
                actor: employee[*]
                do: "Look at the home page"
                assert:
                  - visible_text: {text: "Xoş gəldiniz", within_s: 1}
            """.trimIndent()
    }
}
