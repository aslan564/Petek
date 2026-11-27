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

package az.petek.app.campaign

import az.petek.app.cli.CliRuntime
import az.petek.app.cli.PetekCommand
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.testing.ContractSiteDriver
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import az.petek.core.ids.RunId
import az.petek.core.model.Role
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.Verdict
import az.petek.evidence.infrastructure.SqliteEvidenceStore
import az.petek.faketarget.FakeBug
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import az.petek.identity.domain.IdentityStatus
import az.petek.identity.infrastructure.SqliteIdentityRepository
import az.petek.orchestration.infrastructure.NoOpMonitorView
import az.petek.reporting.domain.RepeatRunEvidence
import az.petek.reporting.domain.StabilityAnalyzer
import com.github.ajalt.clikt.command.test
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The contract site's MVP criteria (docs/PLAN.md "Müqavilə saytının e2e meyarları") proved end to end: the repository's
 * own `scenarios/contract-demo.yaml` (30 testers) run with one command (`petek run`) against the in-process fake target,
 * in real Chromium, with the production object graph. Only the testers' AI is replaced, by [ContractSiteDriver], which
 * reads each prompt as a model would and answers with the agent's own tools; sign-up, joining, codes, events, checks,
 * teardown and the report are Pətək's own. Each run's steps are written to `build/contract-demo/<run id>.txt` for a
 * human look.
 */
@Tag("e2e")
class ContractDemoEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var target: FakeTargetServer
    private val driver = ContractSiteDriver()

    @AfterEach
    fun stop() {
        if (::target.isInitialized) target.close()
    }

    @Test
    fun `the contract demo runs with one command to a passed run, measured, isolated and cleaned up`() =
        runBlocking<Unit> {
            start()

            val out = petek("--json", "run", "scenarios/contract-demo.yaml")

            out.getValue("exitCode").jsonPrimitive.int shouldBe 0
            val run = out.runs().single()
            run.getValue("outcome").jsonPrimitive.content shouldBe "PASSED"
            val runId = run.runId()
            evidence { store, identities ->
                val testers = identities.findByRun(runId)
                testers shouldHaveSize TESTERS
                // Every tester passed its gate: sign-up or joining, the e-mailed and the phone code.
                testers.filterNot { it.status == IdentityStatus.ACTIVE }.shouldBeEmpty()
                // ...and saw its own name once signed in (the isolation proof of every joiner).
                val joined = store.steps(runId).filter { it.action == "run register_and_login" }.mapNotNull { it.detail }
                testers.filter { it.role != Role.ADMIN }.forEach { tester ->
                    joined.any { "The session shows '${tester.displayName}'" in it } shouldBe true
                }
                val assertions = store.assertions(runId)
                assertions.filterNot { it.verdict == Verdict.PASSED }.shouldBeEmpty()
                // The ticket's way through the oracle, the refused approval, one winner of the race.
                assertions.map { it.scenarioStep to it.type }.toSet() shouldContainAll
                    setOf(
                        "announce" to "oracle",
                        "ticket_flow" to "oracle",
                        "ticket_notified" to "oracle",
                        "race" to "only_one_succeeds",
                        "forbidden" to "http_status",
                        "forbidden" to "not_visible",
                    )
                // The announcement reached every employee, each delivery measured from the write.
                store.receipts(runId).count { it.received && it.latencyMs != null } shouldBe EMPLOYEES
            }
            // The teardown deleted the test company the run made.
            target.store.companies.shouldBeEmpty()
            // The report is where the README says (`evidence/<run>/report/index.html`), and `petek report` rebuilds it.
            val report = dir.resolve("evidence/$runId/report/index.html").toRealPath()
            Path.of(run.getValue("report").jsonPrimitive.content).toRealPath() shouldBe report
            Path.of(petek("--json", "report", runId.value).getValue("html").jsonPrimitive.content).toRealPath() shouldBe report
            Files.readString(report) shouldContain runId.value
        }

    @Test
    fun `a site with defects fails the run, and every finding carries its screenshot and the three sources`() =
        runBlocking<Unit> {
            start(
                FakeTargetConfig(
                    bugs = setOf(FakeBug.RACE_DOUBLE_APPROVE, FakeBug.EMPLOYEE_CAN_APPROVE, FakeBug.WRONG_TICKET_STATUS),
                    raceWindow = 5.seconds,
                ),
            )

            val out = petek("--json", "run", "scenarios/contract-demo.yaml")

            out.getValue("exitCode").jsonPrimitive.int shouldBe 1
            val run = out.runs().single()
            run.getValue("outcome").jsonPrimitive.content shouldBe "FAILED"
            val runId = run.runId()
            evidence { store, _ ->
                val findings = store.findings(runId)
                val screenshots = store.artifacts(runId).filter { it.type == ArtifactType.SCREENSHOT }.mapTo(HashSet()) { it.artifactId }
                // Each defect is found where it is, as the site's: the unchanged status (and so no notification to the
                // ticket's author), the second winner, the employee's approval.
                findings.map { it.scenarioStep }.toSet() shouldBe setOf("ticket_flow", "ticket_notified", "race", "forbidden")
                // The second winner is the site's own defect: its answers show it (the owner's decision of 2026-09-30).
                findings.map { it.findingClass }.toSet() shouldBe setOf(FindingClass.BACKEND, FindingClass.SITE_CHECK)
                findings.single { it.scenarioStep == "race" }.findingClass shouldBe FindingClass.SITE_CHECK
                val acted = setOf("ticket_flow", "race", "forbidden")
                findings.forEach { finding ->
                    // What the sender did (A) wherever the tester acted; the receiver (B) or the oracle (C) where checked.
                    if (finding.scenarioStep in acted) finding.a shouldNotBe null
                    if (finding.agentId != null) (finding.b ?: finding.c) shouldNotBe null
                    finding.artifactIds.any { it in screenshots } shouldBe true
                }
                findings.single { it.scenarioStep == "race" }.a!! shouldContain "a03 POST /tickets/t1/approve -> 303"
            }
            target.store.companies.shouldBeEmpty()
        }

    @Test
    fun `three runs of the contract demo give the same result, with no flaky step`() =
        runBlocking<Unit> {
            start()

            val out = petek("--json", "run", "scenarios/contract-demo.yaml", "--repeat", "3")

            out.getValue("exitCode").jsonPrimitive.int shouldBe 0
            val runs = out.runs()
            runs.map { it.getValue("outcome").jsonPrimitive.content } shouldBe List(3) { "PASSED" }
            val rows =
                evidence { store, _ ->
                    StabilityAnalyzer().analyze(
                        runs.map { it.runId() }.map { RepeatRunEvidence(it, store.assertions(it), store.steps(it)) },
                    )
                }
            rows.filter { it.flaky || it.unsteady || it.passed != 3 }.shouldBeEmpty()
            target.store.companies.shouldBeEmpty()
        }

    // --- the command line -----------------------------------------------------------------------------------------

    /** `petek <args>` in-process, as the owner types it in [dir]; the JSON it prints when asked with `--json`. */
    private suspend fun petek(vararg args: String): JsonObject {
        val result = PetekCommand(runtime()).test(args.toList(), width = WIDE)
        dump(result.stdout)
        val json = result.stdout.indexOf('{')
        return if (json < 0) JsonObject(emptyMap()) else Json.parseToJsonElement(result.stdout.substring(json)).jsonObject
    }

    private fun runtime(): CliRuntime =
        CliRuntime(
            environment = { emptyMap() },
            workingDirectory = dir,
            identitySecrets = IdentitySecretSource { error("the configuration names its secret") },
            containers = { config -> AppContainer(config, AppOverrides(llm = driver.client, monitor = NoOpMonitorView)) },
            configureLogging = {},
            observationWindow = Duration.ZERO,
            openInBrowser = { false },
            home = dir.resolve("petek-home"),
            hostResources = HostResourceProbe { HostResources(64L shl 30, 32L shl 30, 32) },
        )

    /** Starts the fake target and lays out the owner's directory: its `.env` and the repository's contract demo. */
    private fun start(config: FakeTargetConfig = FakeTargetConfig()) {
        target = FakeTargetServer(config).start()
        Files.writeString(dir.resolve(".env"), environment())
        Files.createDirectories(dir.resolve("scenarios"))
        Files.copy(repository().resolve("scenarios/contract-demo.yaml"), dir.resolve("scenarios/contract-demo.yaml"))
    }

    private fun environment(): String =
        """
        PETEK_TARGET=${target.baseUrl}
        PETEK_PRODUCTION_HOSTS=
        PETEK_TEST_TOKEN=dev-token
        PETEK_MAILPIT_URL=${target.mailpitUrl}
        PETEK_MAIL_DOMAIN=test.portal.example
        PETEK_IDENTITY_SECRET=contract-demo-e2e-secret
        PETEK_LLM_PROVIDER=none
        PETEK_LLM_CONCURRENCY=8
        PETEK_BROWSER_HEADLESS=true
        PETEK_EVIDENCE_DIR=evidence
        """.trimIndent() + "\n"

    /** Writes the steps of every run named in [stdout] to `build/contract-demo/<run id>.txt`. */
    private suspend fun dump(stdout: String) {
        val runs = RUN_ID.findAll(stdout).map { RunId(it.groupValues[1]) }.toSet()
        if (runs.isEmpty()) return
        val out = Files.createDirectories(Path.of("build/contract-demo"))
        evidence { store, _ ->
            runs.forEach { runId ->
                val types = store.artifacts(runId).associate { it.artifactId to it.type }
                val steps = store.steps(runId).map { "${it.agentId?.value} ${it.scenarioStep} ${it.action} ${it.status} :: ${it.detail}" }
                val findings =
                    store.findings(runId).map {
                        "${it.scenarioStep} ${it.agentId?.value} ${it.findingClass} a=${it.a} | b=${it.b} | c=${it.c} | " +
                            "artifacts=${it.artifactIds.map(types::get)} :: ${it.note}"
                    }
                Files.write(out.resolve("$runId.txt"), steps + "" + "Findings:" + findings)
            }
        }
    }

    private suspend fun <T> evidence(block: suspend (SqliteEvidenceStore, SqliteIdentityRepository) -> T): T =
        SqliteDatabase.open(dir.resolve("evidence/petek.db")).use { db -> block(SqliteEvidenceStore(db), SqliteIdentityRepository(db)) }

    private fun JsonObject.runs(): List<JsonObject> = getValue("runs").jsonArray.map { it.jsonObject }

    private fun JsonObject.runId(): RunId = RunId(getValue("runId").jsonPrimitive.content)

    /** The repository root: the tests run in `app/`. */
    private fun repository(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private companion object {
        const val WIDE = 250

        /** `campaign.testers` of the contract demo. */
        const val TESTERS = 30

        /** `employee[*]` of the contract demo: every one of them reads the announcement. */
        const val EMPLOYEES = 24

        val RUN_ID = Regex("\"runId\": ?\"([^\"]+)\"")
    }
}
