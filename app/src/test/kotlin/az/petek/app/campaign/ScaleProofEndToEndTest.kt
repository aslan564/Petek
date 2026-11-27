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
import az.petek.app.config.ConfigLoader
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.testing.ContractSiteDriver
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.model.Role
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.ABORT_ACTION
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.ROSTER_ACTION
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.UNCOVERED_ACTION
import az.petek.evidence.domain.Verdict
import az.petek.evidence.infrastructure.SqliteEvidenceStore
import az.petek.faketarget.FakeBug
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityStatus
import az.petek.identity.infrastructure.SqliteIdentityRepository
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.infrastructure.NoOpMonitorView
import com.github.ajalt.clikt.command.test
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
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
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The owner's guarantee at any size (2026-10-01): with N testers every one of them works in its own browser session
 * until it got to every step the scenario gave it, nobody is left out silently, and every finding reaches the report.
 * The repository's contract demo is run with `petek run --testers N` for each size of `-Dpetek.scale.testers`
 * (default 30, 50, 100) in real Chromium, with the production object graph against the in-process fake target; only the
 * testers' AI is replaced by [ContractSiteDriver]. What is checked comes from the evidence store, independently of the
 * runner's own bookkeeping: the scenario's actors are resolved again over the run's testers and every tester × step
 * pair must have its own record. Heavy: `./gradlew :app:scaleTest -Ppetek.scale.testers=50`.
 */
@Tag("scale")
class ScaleProofEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var target: FakeTargetServer
    private val driver = ContractSiteDriver()

    @AfterEach
    fun stop() {
        if (::target.isInitialized) target.close()
    }

    @ParameterizedTest(name = "{0} testers")
    @MethodSource("sizes")
    fun `every tester gets to every step it was given, isolated, and the report accounts for all of them`(testers: Int) =
        runBlocking<Unit> {
            start(testers)

            val out = petek("--json", "run", "scenarios/contract-demo.yaml", "--testers", testers.toString())

            val run =
                out
                    .getValue("runs")
                    .jsonArray
                    .single()
                    .jsonObject
            val runId = RunId(run.getValue("runId").jsonPrimitive.content)
            evidence { store, identities ->
                val roster = identities.findByRun(runId)
                val steps = store.steps(runId)
                val assertions = store.assertions(runId)
                withClue(failures(runId, store)) {
                    out.getValue("exitCode").jsonPrimitive.int shouldBe 0
                    run.getValue("outcome").jsonPrimitive.content shouldBe "PASSED"
                }
                // Exactly N testers, each its own: name, e-mail, phone; every one passed its gate.
                roster shouldHaveSize testers
                roster.map { it.email }.toSet() shouldHaveSize testers
                roster.map { it.displayName }.toSet() shouldHaveSize testers
                roster.filterNot { it.status == IdentityStatus.ACTIVE }.shouldBeEmpty()
                // The run planned all of them and left nobody out: no step a tester did not get to, none nobody ran.
                steps.single { it.action == ROSTER_ACTION }.detail shouldBe
                    "$testers testers: " + roster.map { it.agentId }.sorted().joinToString(", ") { it.value }
                steps
                    .filter { it.action == NOT_REACHED_ACTION || it.action == UNCOVERED_ACTION || it.action == ABORT_ACTION }
                    .shouldBeEmpty()
                // Independently of the runner: every tester × step the scenario gives has a record of that tester.
                val acted =
                    (
                        steps.filter { it.kind != StepKind.SYSTEM }.mapNotNull { it.agentId?.let { id -> id to it.scenarioStep } } +
                            assertions.mapNotNull { it.agentId?.let { id -> id to it.scenarioStep } }
                    ).toSet()
                val missing = planned(roster, testers).filterNot { it in acted }
                missing.shouldBeEmpty()
                // Isolation: every joiner's session shows its own name, and only its own records ever show that name.
                val joined = steps.filter { it.action == "run register_and_login" && it.detail != null }
                roster.filter { it.role != Role.ADMIN }.forEach { tester ->
                    val shown = joined.filter { "The session shows '${tester.displayName}'" in it.detail.orEmpty() }
                    shown.shouldNotBeEmpty()
                    shown.map { it.agentId }.toSet() shouldBe setOf(tester.agentId)
                }
                // Every check passed, and the announcement reached every employee, each delivery measured.
                assertions.filterNot { it.verdict == Verdict.PASSED }.shouldBeEmpty()
                store.receipts(runId).count { it.received && it.latencyMs != null } shouldBe roster.count { it.role == Role.EMPLOYEE }
                // The report says so in so many words.
                val report = Files.readString(dir.resolve("evidence/${runId.value}/report/index.html"))
                report shouldContain "Planlanan: $testers tester · İşləyən: $testers · Bütün addımlarını bitirən: $testers"
                report shouldContain "Hər planlanan tester ona verilən bütün addımlara çatdı."
            }
            target.store.companies.shouldBeEmpty()
        }

    @ParameterizedTest(name = "{0} testers")
    @MethodSource("sizes")
    fun `the one tester the site leaves without the announcement is the one finding, whatever the size`(testers: Int) =
        runBlocking<Unit> {
            start(testers, FakeTargetConfig(bugs = setOf(FakeBug.DROP_NOTIFICATION_FOR_ONE_USER)))

            val out = petek("--json", "run", "scenarios/contract-demo.yaml", "--testers", testers.toString())

            out.getValue("exitCode").jsonPrimitive.int shouldBe 1
            val runId =
                RunId(
                    out
                        .getValue("runs")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("runId")
                        .jsonPrimitive.content,
                )
            evidence { store, identities ->
                val roster = identities.findByRun(runId)
                // The site drops the first employee of the announcement's audience in e-mail order.
                val victim = roster.filter { it.role == Role.EMPLOYEE }.minBy { it.email }.agentId
                val findings = store.findings(runId)
                findings.shouldNotBeEmpty()
                findings.map { it.agentId }.toSet() shouldContainExactly setOf(victim)
                findings.map { it.scenarioStep }.toSet() shouldContainExactly setOf("read_announce")
                // Every other employee read it, and nobody was left out.
                store
                    .assertions(runId)
                    .filter { it.scenarioStep == "read_announce" && it.agentId != victim }
                    .filterNot { it.verdict == Verdict.PASSED }
                    .shouldBeEmpty()
                store.steps(runId).filter { it.action == NOT_REACHED_ACTION || it.action == UNCOVERED_ACTION }.shouldBeEmpty()
                val report = Files.readString(dir.resolve("evidence/${runId.value}/report/index.html"))
                report shouldContain "read_announce · ${roster.single { it.agentId == victim }.displayName} (${victim.value})"
            }
        }

    /** Every tester × step pair the scenario gives at this size, resolved again from the run's testers. */
    private fun planned(
        roster: List<Identity>,
        testers: Int,
    ): List<Pair<AgentId, String>> =
        container().use { container ->
            val file = dir.resolve("scenarios/contract-demo.yaml")
            val campaign = CampaignScaler.scale(container.campaigns.execute(file, container.knownRunFunctions), testers)
            val resolver = DefaultActorResolver()
            campaign.allSteps.flatMap { step -> resolver.resolve(step.actors, roster).map { it.agentId to step.id } }
        }

    /** What did not pass, for the message of a failed size. */
    private suspend fun failures(
        runId: RunId,
        store: SqliteEvidenceStore,
    ): String =
        store
            .steps(runId)
            .filter { it.status.name != "PASSED" }
            .take(MAX_LISTED)
            .joinToString("\n") { "${it.agentId?.value} ${it.scenarioStep} ${it.action} ${it.status} :: ${it.detail}" }

    // --- the command line, as the contract demo end-to-end test runs it -------------------------------------------

    private suspend fun petek(vararg args: String): JsonObject {
        val result = PetekCommand(runtime()).test(args.toList(), width = WIDE)
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
        )

    private fun container(): AppContainer {
        val config =
            ConfigLoader(
                emptyMap(),
                dir,
                IdentitySecretSource { error("the configuration names its secret") },
            ).load(dir.resolve(".env"))
        return AppContainer(config, AppOverrides(llm = driver.client, monitor = NoOpMonitorView))
    }

    private fun start(
        testers: Int,
        config: FakeTargetConfig = FakeTargetConfig(),
    ) {
        target = FakeTargetServer(config).start()
        Files.writeString(
            dir.resolve(".env"),
            """
            PETEK_TARGET=${target.baseUrl}
            PETEK_PRODUCTION_HOSTS=
            PETEK_TEST_TOKEN=dev-token
            PETEK_MAILPIT_URL=${target.mailpitUrl}
            PETEK_MAIL_DOMAIN=test.portal.example
            PETEK_IDENTITY_SECRET=scale-proof-e2e-secret-$testers
            PETEK_LLM_PROVIDER=none
            PETEK_BROWSER_HEADLESS=true
            PETEK_EVIDENCE_DIR=evidence
            """.trimIndent() + "\n",
        )
        Files.createDirectories(dir.resolve("scenarios"))
        Files.copy(repository().resolve("scenarios/contract-demo.yaml"), dir.resolve("scenarios/contract-demo.yaml"))
    }

    private suspend fun <T> evidence(block: suspend (SqliteEvidenceStore, SqliteIdentityRepository) -> T): T =
        SqliteDatabase.open(dir.resolve("evidence/petek.db")).use { db -> block(SqliteEvidenceStore(db), SqliteIdentityRepository(db)) }

    private fun repository(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    companion object {
        private const val WIDE = 250
        private const val MAX_LISTED = 40

        /** The sizes to prove, `-Dpetek.scale.testers=30,50,100` (the default). */
        @JvmStatic
        fun sizes(): List<Int> =
            System
                .getProperty("petek.scale.testers", "30,50,100")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map(String::toInt)
    }
}
