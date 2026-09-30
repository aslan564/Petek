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

import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.diagnostics.TargetReachability
import az.petek.app.runs.RunLock
import az.petek.app.testing.CliHarness
import az.petek.app.testing.CliHarness.Companion.done
import az.petek.app.testing.CliHarness.Companion.tinyCampaign
import az.petek.app.testing.FakeBrowserEngine
import az.petek.app.testing.scriptedLlm
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.domain.RunResult
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.testing.OwnershipTestKit
import az.petek.ownership.testing.ScriptedOwnershipProbe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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
import java.net.URI
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
    fun `a run that did not pass only because checks could not be decided exits with 3, and a failure elsewhere wins`() {
        fun summary(
            outcome: RunOutcome,
            failed: Int = 0,
            undecided: Int = 0,
        ) = RunSummary(RunId("run_x"), outcome, 3, 0, failed, 0, null, 10, undecided)

        RunCommand.exitCodeOf(listOf(summary(RunOutcome.FAILED, undecided = 1))) shouldBe ExitCodes.INCONCLUSIVE
        RunCommand.exitCodeOf(listOf(summary(RunOutcome.FAILED, undecided = 1), summary(RunOutcome.FAILED, failed = 1))) shouldBe
            ExitCodes.FAILURE
        RunCommand.exitCodeOf(listOf(summary(RunOutcome.FAILED, undecided = 1), summary(RunOutcome.ABORTED))) shouldBe
            ExitCodes.CONFIG_OR_ABORTED
        RunCommand.exitCodeOf(listOf(summary(RunOutcome.PASSED))) shouldBe ExitCodes.OK
    }

    @Test
    fun `a run the panel holds over the same evidence keeps petek run from starting`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign())
            val lock = RunLock(cli.evidenceDir.resolve(RunLock.FILE_NAME))

            val result = lock.acquire("the panel").use { cli.run("run", "tiny.yaml") }

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain "Another run is going over this evidence store (the panel"
            cli.evidence { it.evidence.latest() } shouldBe null
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
    fun `a stage whose owner has not proved it is refused with the proof to publish, and nothing is tested`() =
        runBlocking<Unit> {
            val cli =
                CliHarness(
                    dir,
                    mapOf("PETEK_TARGET" to "https://stage.example.com"),
                    ownership = OwnershipTestKit.unowned(FakeHarnessClock()),
                )
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            result.stderr shouldContain
                "Pətək writes to a site only after its owner proves ownership, so nothing was tested on stage.example.com"
            result.stderr shouldContain "https://stage.example.com/.well-known/petek-verification.txt"
            result.stderr shouldContain "_petek-verification.stage.example.com"
            result.stderr shouldContain "This campaign is not one: it has companies (tenant: company)"
            result.stderr shouldContain "step 'signup' is a `do` step"
            cli.browser.options.shouldBeEmpty()
            cli.evidence { it.evidence.latest() } shouldBe null
        }

    @Test
    fun `a visitor run starts on a stage whose owner has not proved it, and says so`() =
        runBlocking<Unit> {
            val cli =
                CliHarness(
                    dir,
                    mapOf("PETEK_TARGET" to "https://stage.example.com"),
                    ownership = OwnershipTestKit.unowned(FakeHarnessClock()),
                )
            cli.write("visit.yaml", VISITOR_CAMPAIGN)

            val result = cli.run("run", "visit.yaml")

            result.statusCode shouldBe 0
            result.stdout shouldContain "stage.example.com has not proved its ownership, so 'visit' runs as a visitor run"
            cli.evidence { it.evidence.latest() }.shouldNotBeNull().result shouldBe RunResult.PASSED
        }

    @Test
    fun `a visitor run on a stage whose owner has not proved it sends no one through the owner's proxies`() =
        runBlocking<Unit> {
            val cli =
                CliHarness(
                    dir,
                    mapOf("PETEK_TARGET" to "https://stage.example.com", "PETEK_PROXIES" to "http://10.0.0.1:3128,http://10.0.0.2:3128"),
                    ownership = OwnershipTestKit.unowned(FakeHarnessClock()),
                )
            cli.write("visit.yaml", VISITOR_CAMPAIGN)

            val result = cli.run("run", "visit.yaml")

            result.statusCode shouldBe 0
            result.stderr shouldContain "PETEK_PROXIES is not used here"
            cli.browser.options.map { it.proxy } shouldBe listOf(null, null)
        }

    @Test
    fun `on a stage whose owner proved it every tester goes out through its own proxy`() =
        runBlocking<Unit> {
            val cli =
                CliHarness(
                    dir,
                    mapOf("PETEK_TARGET" to "https://stage.example.com", "PETEK_PROXIES" to "http://10.0.0.1:3128,http://10.0.0.2:3128"),
                    ownership = OwnershipTestKit.siteOwnership(FakeHarnessClock(), ScriptedOwnershipProbe()),
                )
            cli.write("tiny.yaml", tinyCampaign())

            cli.run("run", "tiny.yaml").statusCode shouldBe 0

            cli.browser.options
                .map { it.proxy?.server }
                .toSet() shouldBe setOf("http://10.0.0.1:3128", "http://10.0.0.2:3128")
        }

    @Test
    fun `a stage whose owner has published the proof is tested`() =
        runBlocking<Unit> {
            val probe = ScriptedOwnershipProbe()
            val cli =
                CliHarness(
                    dir,
                    mapOf("PETEK_TARGET" to "https://stage.example.com"),
                    ownership = OwnershipTestKit.siteOwnership(FakeHarnessClock(), probe),
                )
            cli.write("tiny.yaml", tinyCampaign())

            cli.run("run", "tiny.yaml").statusCode shouldBe 0

            probe.looks.map { it.host } shouldBe listOf("stage.example.com")
        }

    @Test
    fun `checks that call the site's API on its own host need that host proved as well, and never a production one`() =
        runBlocking<Unit> {
            val probe = ScriptedOwnershipProbe(found = null)
            val cli = CliHarness(dir, ownership = OwnershipTestKit.siteOwnership(FakeHarnessClock(), probe, local = setOf("127.0.0.1")))
            cli.write("api.yaml", API_CAMPAIGN)

            val refused = cli.run("run", "api.yaml")

            refused.statusCode shouldBe ExitCodes.CONFIG_OR_ABORTED
            refused.stderr shouldContain "nothing was tested on api.stage.example.com"
            refused.stderr shouldContain "https://api.stage.example.com/.well-known/petek-verification.txt"
            refused.stderr shouldContain "Its http_status checks call the site's API at api.stage.example.com"
            cli.browser.options.shouldBeEmpty()

            // Proved: the check calls the API host, and the testers' sessions know it for the page's own token.
            probe.found = OwnershipMethod.WELL_KNOWN_FILE
            cli.run("run", "api.yaml").statusCode shouldBe ExitCodes.FAILURE
            cli.browser.sessions
                .flatMap { it.actions }
                .filter { it.startsWith("request") } shouldContain
                "request POST https://api.stage.example.com/v1/tickets/1/approve"
            cli.browser.options
                .map { it.apiOrigin }
                .toSet() shouldBe setOf(URI("https://api.stage.example.com"))

            cli.env["PETEK_PRODUCTION_HOSTS"] = "api.stage.example.com"
            cli.run("run", "api.yaml").stderr shouldContain "Refusing to contact the target"
        }

    @Test
    fun `a local target needs no proof`() =
        runBlocking<Unit> {
            val probe = ScriptedOwnershipProbe(found = null)
            val cli = CliHarness(dir, ownership = OwnershipTestKit.siteOwnership(FakeHarnessClock(), probe, local = setOf("127.0.0.1")))
            cli.write("tiny.yaml", tinyCampaign())

            cli.run("run", "tiny.yaml").statusCode shouldBe 0

            probe.looks.shouldBeEmpty()
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
    fun `more testers live at once than this machine is advised to carry is said before the run, which goes on`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir).apply { hostResources = HostResourceProbe { HostResources(2L shl 30, 256L shl 20, 1) } }
            cli.write("tiny.yaml", tinyCampaign(testers = 4))

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 0
            result.stderr shouldContain "4 testers live at once is more than this machine is advised to carry"
        }

    @Test
    fun `a swarm with codes typed by hand is warned about, since that suits the explorer's few sessions`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir, mapOf("PETEK_MAIL_SOURCE" to "manual"))
            cli.write("tiny.yaml", tinyCampaign(testers = 4))

            val result = cli.run("run", "tiny.yaml")

            result.statusCode shouldBe 0
            result.stderr shouldContain "PETEK_MAIL_SOURCE=manual: you type every tester's e-mail code in the panel, 4 of them"
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
    fun `a race that the waves leave with one racer is announced before the run`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write(
                "waves.yaml",
                """
                campaign:
                  name: waves
                  testers: 5
                  seed: 3
                  wave_size: 2
                  roles: {admin: 1, manager: 3, employee: 1}
                  departments: [IT, HR]
                  registration: {invite: 3, company_code: 1}
                  budget: {max_steps_per_agent: 5, max_minutes: 2}
                setup:
                  - id: signup
                    actor: admin
                    do: "Sign up"
                steps:
                  - id: race
                    actor: manager[*]
                    parallel: true
                    do: "Approve the same ticket"
                    assert:
                      - only_one_succeeds: {request: "POST .*/approve"}
                """,
            )

            val result = cli.run("run", "waves.yaml")

            // Three racing managers, waves of two: a02 and a03 race in wave 1, a04 is alone in wave 2.
            result.stderr shouldContain "Warning: with campaign.wave_size 2, race step 'race'"
            result.stderr shouldContain "has a single racer in wave 2, where it never passes (inconclusive)"
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

    private companion object {
        /** An owner and an employee whose check calls the site's API on its own host (a full api_prefix). */
        val API_CAMPAIGN =
            """
            campaign:
              name: api
              testers: 2
              seed: 7
              roles: {admin: 1, manager: 0, employee: 1}
              departments: [IT]
              budget: {max_steps_per_agent: 5, max_minutes: 2}
            target_profile:
              api_prefix: https://api.stage.example.com/v1
            setup:
              - id: signup
                actor: admin
                do: "Sign up and create the company"
            steps:
              - id: look
                actor: employee[*]
                do: "Look at the home page"
                assert:
                  - http_status: {path: "{api}/tickets/1/approve", method: POST, equals: 403}
            """.trimIndent()

        /** Two visitors that only read: the gate `guest` and the read-only `site_health`. */
        val VISITOR_CAMPAIGN =
            """
            campaign:
              name: visit
              testers: 2
              seed: 7
              tenant: none
              roles: {visitor: 2}
              registration: {guest: 2}
              budget: {max_steps_per_agent: 5, max_minutes: 2}
            setup:
              - id: gates
                actor: visitor[*]
                run: register_and_login
            steps:
              - id: health
                actor: visitor[n=1]
                run: {function: site_health, args: {checks: "console,mobile", pages: "/"}}
            """.trimIndent()
    }
}
