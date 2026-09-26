/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.diagnostics.TargetReachability
import az.petek.app.testing.CliHarness
import az.petek.app.testing.CliHarness.Companion.done
import az.petek.app.testing.CliHarness.Companion.tinyCampaign
import az.petek.app.testing.FakeBrowserEngine
import az.petek.app.testing.scriptedLlm
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.RunResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class RunCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a passing run prints its summary and report and exits with 0`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Running 'tiny' with 2 agents against ${CliHarness.UNUSED_TARGET}"
            result.stdout shouldContain ": PASSED in "
            val report =
                Regex("Report: (\\S+)")
                    .find(result.stdout)
                    ?.groupValues
                    ?.get(1)
                    .shouldNotBeNull()
            Files.isRegularFile(Path.of(report)) shouldBe true
            Files.isRegularFile(Path.of(report).resolveSibling("report.md")) shouldBe true
            cli.evidence { it.evidence.latest() }?.result shouldBe RunResult.PASSED
        }

    @Test
    fun `a site that does not answer is reported and nothing is tested in its place`() =
        runBlocking<Unit> {
            val cli =
                CliHarness(dir, reachability = TargetReachability { TargetAnswer.Unreachable("ConnectException: Connection refused") })
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain "The site under test does not answer, so nothing was tested: ${CliHarness.UNUSED_TARGET}"
            result.stderr shouldContain "Connection refused"
            cli.browser.options.shouldBeEmpty()
            cli.evidence { it.evidence.latest() } shouldBe null
        }

    @Test
    fun `the LLM usage of every agent is stored with the run`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign(testers = 3))

            cli.run("run", "tiny.yaml").statusCode shouldBe 0

            val usage = cli.evidence { stores -> stores.evidence.usage(stores.evidence.latest()!!.runId) }
            usage.map { it.agentId } shouldContainExactlyInAnyOrder listOf(AgentId("a01"), AgentId("a02"), AgentId("a03"))
            usage.all { it.calls == 1 && it.inputTokens == 100L && it.outputTokens == 20L } shouldBe true
        }

    @Test
    fun `the agents use the browser configuration of the environment`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, mapOf("PETEK_BROWSER_TOPOLOGY" to "per-session"))
            cli.write("tiny.yaml", tinyCampaign())

            cli.run("run", "tiny.yaml", "--headful").statusCode shouldBe 0

            cli.browser.configs
                .single()
                .headless shouldBe false
            cli.browser.configs
                .single()
                .topology.name shouldBe "PER_SESSION"
            cli.browser.sessions shouldHaveSize 2
            cli.browser.sessions.all { it.closed } shouldBe true
            cli.browser.stopCount shouldBe 1
        }

    @Test
    fun `a failed step makes the run FAILED and exits with 1`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, llm = scriptedLlm { request -> done(success = !request.label.endsWith("/look")) })
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 1
            result.stdout shouldContain ": FAILED in "
        }

    @Test
    fun `a failed admin setup aborts the run and exits with 2`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, llm = scriptedLlm { request -> done(success = !request.label.endsWith("/signup")) })
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 2
            result.stdout shouldContain ": ABORTED in "
            result.stdout shouldContain "Report: "
        }

    @Test
    fun `a browser that cannot start aborts the run but still writes the report`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, browser = FakeBrowserEngine(failStart = "Chromium is missing"))
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 2
            result.stdout shouldContain ": ABORTED in "
            cli.evidence { it.evidence.latest() }?.result shouldBe RunResult.ABORTED
        }

    @Test
    fun `a cancelled run (Ctrl+C) still stops the browser and writes its report`() =
        runBlocking<Unit> {
            val agentsWorking = CompletableDeferred<Unit>()
            val cli =
                CliHarness(
                    dir,
                    llm =
                        scriptedLlm { request ->
                            if (request.label.endsWith("/look")) {
                                agentsWorking.complete(Unit)
                                awaitCancellation()
                            }
                            done()
                        },
                )
            cli.write("tiny.yaml", tinyCampaign())

            val command = launch(Dispatchers.Default) { cli.run("run", "tiny.yaml") }
            withTimeout(30.seconds) { agentsWorking.await() }
            command.cancelAndJoin()

            val run = cli.evidence { it.evidence.latest() }.shouldNotBeNull()
            run.result shouldBe RunResult.ABORTED
            Files.isRegularFile(cli.evidenceDir.resolve("${run.runId}/report/${RunCommand.HTML_REPORT}")) shouldBe true
            cli.browser.sessions.all { it.closed } shouldBe true
            cli.browser.stopCount shouldBe 1
        }

    @Test
    fun `repeated runs form one stability group`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml", "--repeat", "2")

            result.statusCode shouldBe 0
            result.stdout shouldContain "2 times"
            Regex(": PASSED in ").findAll(result.stdout).count() shouldBe 2
            val latest = cli.evidence { it.evidence.latest() }.shouldNotBeNull()
            cli.evidence { it.evidence.byRepeatGroup(latest.repeatGroup!!) } shouldHaveSize 2
        }

    @Test
    fun `--testers runs a smaller version of the campaign`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign(testers = 6))

            val result = cli.run("run", "tiny.yaml", "--testers", "3")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Running 'tiny (3 testers, scaled from 6)' with 3 agents"
            val latest = cli.evidence { it.evidence.latest() }.shouldNotBeNull()
            cli.evidence { it.identities.findByRun(latest.runId) } shouldHaveSize 3
        }

    @Test
    fun `--testers warns about steps nobody can run anymore`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write(
                "depts.yaml",
                """
                campaign:
                  name: depts
                  testers: 5
                  seed: 3
                  roles: {admin: 1, manager: 0, employee: 4}
                  departments: [IT, HR]
                  budget: {max_steps_per_agent: 5, max_minutes: 2}
                setup:
                  - id: signup
                    actor: admin
                    do: "Sign up"
                steps:
                  - id: hr_second
                    actor: employee[dept=HR, n=2]
                    do: "Only the second HR employee"
                """,
            )

            val result = cli.run("run", "depts.yaml", "--testers", "3")

            result.statusCode shouldBe 0
            result.stderr shouldContain "Warning: with --testers 3 no tester matches 'employee[dept=HR, n=2]', so step 'hr_second'"
        }

    @Test
    fun `--testers above the campaign's testers runs a bigger version of it`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml", "--testers", "5")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Running 'tiny (5 testers, scaled from 2)' with 5 agents"
            val latest = cli.evidence { it.evidence.latest() }.shouldNotBeNull()
            cli.evidence { it.identities.findByRun(latest.runId) } shouldHaveSize 5
        }

    @Test
    fun `--agents is still accepted as the old name of --testers`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign(testers = 4))

            val result = cli.run("run", "tiny.yaml", "--agents", "2")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Running 'tiny (2 testers, scaled from 4)' with 2 agents"
        }

    @Test
    fun `a production target is refused before anything runs`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, mapOf("PETEK_PRODUCTION_HOSTS" to "127.0.0.1"))
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 2
            result.stderr shouldContain "Refusing to contact the target"
            result.stderr shouldContain "PETEK_ALLOW_PRODUCTION"
            cli.browser.configs.shouldBeEmpty()
            Files.exists(cli.dbPath) shouldBe false
        }

    @Test
    fun `an invalid campaign exits with 2`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign().replace("do: \"Look at the home page\"", "run: nope"))

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 2
            result.stderr shouldContain "nope"
        }

    @Test
    fun `the answer of the agent decides nothing about the verdict of an assertion`() =
        runBlocking<Unit> {
            val cli =
                CliHarness(
                    dir,
                    llm =
                        scriptedLlm {
                            buildJsonObject {
                                put("reason", "claims success without checking")
                                put("tool", "done")
                                put("summary", "all good")
                            }
                        },
                )
            cli.write(
                "assert.yaml",
                tinyCampaign() +
                    "\n    assert:\n      - visible_text: {text: \"Never shown\", within_s: 1}",
            )

            val result = cli.run("run", "assert.yaml")

            result.statusCode shouldBe 1
            result.stdout shouldContain "assertions failed 1"
        }
}
