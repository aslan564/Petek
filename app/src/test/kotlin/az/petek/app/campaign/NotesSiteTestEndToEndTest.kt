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
import az.petek.app.testing.DraftSiteDriver
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import az.petek.core.ids.RunId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.evidence.infrastructure.SqliteEvidenceStore
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.infrastructure.SqliteExplorationRepository
import az.petek.faketarget.notes.FakeNotesServer
import az.petek.faketarget.notes.NotesBug
import az.petek.orchestration.infrastructure.NoOpMonitorView
import com.github.ajalt.clikt.command.test
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The universal criterion (docs/PLAN.md Faza 13 and 25) proved end to end: on a site unlike the contract, a notes
 * application with a test API but no companies, `petek test` explores the site, drafts the scenario only from what it
 * found, approves and runs it in real Chromium with the production object graph, and reports. No company, invitation,
 * code or contract resource is assumed on the way. The explorer's trial touch writes one marked note from the account
 * it signed up with itself (the owner's decision of 2026-09-30: on a site without companies that account is the test
 * data), so the draft knows what the test API serves and where a note's own page is. Only the AI is replaced, by
 * [DraftSiteDriver], which knows no site. The draft and the run's steps are written to `build/notes-test/` for a human
 * look.
 */
@Tag("e2e")
class NotesSiteTestEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeNotesServer
    private val driver = DraftSiteDriver()

    @AfterEach
    fun stop() {
        if (::site.isInitialized) site.close()
    }

    @Test
    fun `a site with a test API but no companies is explored, drafted, run and reported by one command`() =
        runBlocking<Unit> {
            start()

            val out = test()

            withClue(out.toString()) {
                out.getValue("stage").jsonPrimitive.content shouldBe "FINISHED"
                out.getValue("result").jsonPrimitive.content shouldBe "PASSED"
                out.getValue("exitCode").jsonPrimitive.int shouldBe 0
            }
            val draft = draft(out)
            // Drafted only from what the explorer found: a site without companies, whose testers sign up through the
            // form it found and write notes with the button it saw; a test API alone is no sign of companies.
            draft shouldContain "tenant: none"
            draft shouldContain "register_and_login"
            draft shouldContain "note-submit-happy"
            listOf("register_owner", "seed_company", "company", "invite", "announcement", "ticket").forEach { draft shouldNotContain it }
            // The trial touch proved what the test API serves and where a note's own page is: the draft checks both.
            draft shouldContain "oracle: {path: \"/test/notes/{last_id}\""
            draft shouldContain "note-submit-direct-url"
            // It wrote from one account only, the explorer's own, and marked what it wrote.
            site.allNotes
                .filter { "Pətək sınaq" in it.title }
                .map { it.author }
                .distinct()
                .size shouldBe 1
            val runId = RunId(out.getValue("runId").jsonPrimitive.content)
            evidence { store ->
                val assertions = store.assertions(runId)
                withClue(assertions.joinToString("\n") { "${it.scenarioStep} ${it.type} ${it.verdict} ${it.note}" }) {
                    assertions.filter { it.verdict == Verdict.FAILED }.size shouldBe 0
                    assertions.filter { it.scenarioStep == "note-submit-happy" && it.verdict == Verdict.PASSED }.shouldNotBeEmpty()
                }
            }
            // The testers wrote their notes on the site, each with the draft's own marker.
            site.allNotes.filter { "Pətək yoxlaması" in it.title }.shouldNotBeEmpty()
            Files.exists(Path.of(out.getValue("report").jsonPrimitive.content)) shouldBe true
        }

    @Test
    fun `a dead link on the site fails the run on the checks the draft wrote for the pages the explorer saw`() =
        runBlocking<Unit> {
            start(NotesBug.DEAD_LINK)

            val out = test()

            withClue(out.toString()) {
                out.getValue("result").jsonPrimitive.content shouldBe "FAILED"
                out.getValue("exitCode").jsonPrimitive.int shouldBe 1
            }
            val runId = RunId(out.getValue("runId").jsonPrimitive.content)
            evidence { store ->
                val steps = store.steps(runId)
                withClue(steps.joinToString("\n") { "${it.scenarioStep} ${it.action} ${it.status} ${it.detail}" }) {
                    steps.filter { "unhealthy_page" in it.detail.orEmpty() && "/help" in it.detail.orEmpty() }.shouldNotBeEmpty()
                }
            }
        }

    @Test
    fun `a note that opens for anyone who types its address fails the run on the draft's own direct address check`() =
        runBlocking<Unit> {
            start(NotesBug.FOREIGN_NOTE_VISIBLE)

            val out = test()

            withClue(out.toString()) {
                out.getValue("result").jsonPrimitive.content shouldBe "FAILED"
                out.getValue("exitCode").jsonPrimitive.int shouldBe 1
            }
            val runId = RunId(out.getValue("runId").jsonPrimitive.content)
            evidence { store ->
                val steps = store.steps(runId)
                withClue(steps.joinToString("\n") { "${it.scenarioStep} ${it.action} ${it.status} ${it.detail}" }) {
                    steps
                        .filter { it.scenarioStep == "note-submit-direct-url" && it.status == StepStatus.FAILED }
                        .shouldNotBeEmpty()
                }
            }
        }

    private fun start(vararg bugs: NotesBug) {
        site = FakeNotesServer(bugs.toSet(), testToken = TOKEN).start()
        Files.writeString(
            dir.resolve(".env"),
            """
            PETEK_TARGET=${site.baseUrl}
            PETEK_PRODUCTION_HOSTS=
            PETEK_TEST_TOKEN=$TOKEN
            PETEK_MAILPIT_URL=http://127.0.0.1:9
            PETEK_IDENTITY_SECRET=notes-test-e2e-secret
            PETEK_LLM_PROVIDER=codex-cli
            PETEK_LLM_CONCURRENCY=6
            PETEK_BROWSER_HEADLESS=true
            PETEK_EVIDENCE_DIR=evidence
            """.trimIndent() + "\n",
        )
    }

    /**
     * `petek --json test` with the trial touch and three testers; the JSON it printed. The draft and the run's steps
     * are written to `build/notes-test/` before anything is asserted, for a human look.
     */
    private suspend fun test(): JsonObject {
        val result =
            PetekCommand(runtime()).test(
                listOf("--json", "test", "--allow-writes", "--testers", "3", "--max-pages", "10", "--max-minutes", "5"),
                width = WIDE,
            )
        val json = result.stdout.indexOf('{')
        check(json >= 0) { "petek test printed no JSON: ${result.stdout} ${result.stderr}" }
        val out = Json.parseToJsonElement(result.stdout.substring(json)).jsonObject
        dump(out)
        return out
    }

    private suspend fun dump(out: JsonObject) {
        val folder = Files.createDirectories(Path.of("build/notes-test"))
        out["explorationId"]?.jsonPrimitive?.contentOrNull?.let { id ->
            val drafts =
                SqliteDatabase.open(dir.resolve("evidence/petek.db")).use { db ->
                    SqliteExplorationRepository(db).drafts(ExplorationId(id))
                }
            drafts.lastOrNull()?.let { Files.writeString(folder.resolve("$id.yaml"), it.yaml) }
        }
        out["runId"]?.jsonPrimitive?.contentOrNull?.let { id ->
            evidence { store ->
                val steps =
                    store
                        .steps(
                            RunId(id),
                        ).map { "${it.agentId?.value} ${it.scenarioStep} ${it.action} ${it.status} :: ${it.detail}" }
                Files.write(folder.resolve("$id.txt"), steps)
            }
        }
    }

    /** The draft the run's scenario came from. */
    private suspend fun draft(out: JsonObject): String {
        val exploration = ExplorationId(out.getValue("explorationId").jsonPrimitive.content)
        return SqliteDatabase.open(dir.resolve("evidence/petek.db")).use { db ->
            SqliteExplorationRepository(db).drafts(exploration).last().yaml
        }
    }

    private suspend fun <T> evidence(block: suspend (SqliteEvidenceStore) -> T): T =
        SqliteDatabase.open(dir.resolve("evidence/petek.db")).use { db -> block(SqliteEvidenceStore(db)) }

    private fun runtime(): CliRuntime =
        CliRuntime(
            environment = { emptyMap() },
            workingDirectory = dir,
            identitySecrets = IdentitySecretSource { error("the configuration names its secret") },
            containers = { config -> AppContainer(config, AppOverrides(llm = driver.client, monitor = NoOpMonitorView)) },
            configureLogging = {},
            observationWindow = Duration.ZERO,
            panelContainers = { config, overrides -> AppContainer(config, overrides.copy(llm = driver.client)) },
            openInBrowser = { false },
            home = dir.resolve("petek-home"),
            hostResources = HostResourceProbe { HostResources(64L shl 30, 32L shl 30, 32) },
        )

    private companion object {
        const val WIDE = 250
        const val TOKEN = "notes-test-token"
    }
}
