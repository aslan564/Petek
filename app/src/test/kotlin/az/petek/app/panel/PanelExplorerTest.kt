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

package az.petek.app.panel

import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.panel.explorer.RoleSessionSource
import az.petek.app.panel.explorer.RoleSessions
import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelWaits
import az.petek.app.testing.PanelWaits.ended
import az.petek.app.testing.PanelWaits.exploration
import az.petek.app.testing.PanelWaits.explored
import az.petek.browser.domain.SessionOptions
import az.petek.core.ids.ArtifactId
import az.petek.core.testing.FakeHarnessClock
import az.petek.dashboard.domain.ExplorationPhase
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.ModelChangeKind
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PhaseState
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.ownership.application.SiteOwnership
import az.petek.ownership.testing.OwnershipTestKit
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The "Kəşfiyyat" screen's backend with production wiring over a scripted site and a scripted LLM. */
class PanelExplorerTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()

    @AfterEach
    fun close() = open.forEach { it.close() }

    private fun harness(
        allowProduction: Boolean = false,
        roleSessions: RoleSessionSource? = null,
        llm: az.petek.app.testing.PanelLlm =
            az.petek.app.testing
                .PanelLlm(),
        ownership: SiteOwnership = OwnershipTestKit.owned(FakeHarnessClock()),
        site: URI = URI("http://127.0.0.1:9"),
    ): PanelHarness =
        PanelHarness(
            dir,
            site = PanelWaits.site(site),
            llm = llm,
            allowProduction = allowProduction,
            ownership = ownership,
            roleSessions =
                roleSessions?.let { source ->
                    { source }
                },
        ).also { open += it }

    @Test
    fun `a production target is refused with the reason under the target field and nothing starts`() =
        runBlocking<Unit> {
            val panel = harness()

            val refusal =
                shouldThrow<PanelRequestException> { panel.backend.startExploration(PanelHarness.instructions("https://Portal.example/")) }

            refusal.problems.single().field shouldBe PanelInstructions.TARGET
            refusal.problems.single().message shouldContain "'portal.example' istehsal ünvanıdır"
            panel.backend.exploration().shouldBeNull()
            panel.site.startCount shouldBe 0
        }

    @Test
    fun `a site that does not answer is reported under the target field and nothing is explored in its place`() =
        runBlocking<Unit> {
            val panel =
                PanelHarness(
                    dir,
                    site = PanelWaits.site(),
                    reachability = { TargetAnswer.Unreachable("UnknownHostException: site.example") },
                ).also { open += it }

            val refusal =
                shouldThrow<PanelRequestException> { panel.backend.startExploration(PanelHarness.instructions(panel.site.base.toString())) }

            refusal.problems.single().field shouldBe PanelInstructions.TARGET
            refusal.problems.single().message shouldContain "Verilən sayt cavab vermir, ona görə heç nə test edilmədi"
            refusal.problems.single().message shouldContain "UnknownHostException: site.example"
            panel.backend.exploration().shouldBeNull()
            panel.site.startCount shouldBe 0
        }

    @Test
    fun `instructions longer than the explorer reads are refused under the instructions field`() =
        runBlocking<Unit> {
            val panel = harness()

            val refusal =
                shouldThrow<PanelRequestException> {
                    panel.backend.startExploration(PanelHarness.instructions(panel.site.base.toString(), text = "x".repeat(4_001)))
                }

            refusal.problems.single().field shouldBe PanelInstructions.INSTRUCTIONS
        }

    @Test
    fun `an exploration walks the site in the background and ends with the stored model, ideas and a draft preview`() =
        runBlocking<Unit> {
            val panel = harness()

            val view = panel.explored()

            view.status shouldBe ExplorationStatus.FINISHED
            view.target shouldBe "http://127.0.0.1:9"
            view.phases.map { it.phase to it.state } shouldBe
                listOf(
                    ExplorationPhase.ANONYMOUS to PhaseState.DONE,
                    ExplorationPhase.ROLE_BASED to PhaseState.SKIPPED,
                    ExplorationPhase.TRIAL_TOUCH to PhaseState.SKIPPED,
                )
            view.activity.map { it.text }.any { it.contains("Rollarla gəzinti daxil olmuş sessiya istəyir") } shouldBe true
            view.visited.map { it.url } shouldContainAll listOf("http://127.0.0.1:9/login", "http://127.0.0.1:9/join")
            val login = view.model.pages.single { it.urlPattern == "/login" }
            login.forms
                .single()
                .fields
                .map { it.label } shouldBe listOf("E-poçt", "Parol")
            login.actions.single().kind shouldBe "LOGIN"
            view.ideas.map { it.pattern } shouldContain "HAPPY_PATH"
            view.unknowns.single().question shouldBe "Şirkət kodu haradan alınır?"
            view.draftYaml.shouldNotBeNull() shouldContain "register_owner"
            val shot =
                view.visited
                    .first()
                    .screenshotArtifactId
                    .shouldNotBeNull()
            panel.backend
                .explorationArtifact(shot)
                .shouldNotBeNull()
                .type.name shouldBe "SCREENSHOT"
            panel.backend.explorationArtifact(ArtifactId("art_unknown")).shouldBeNull()
            eventually(5.seconds) { panel.site.stopCount shouldBe 1 }
            panel.llm.explorerPrompts.first() shouldContain "Owner's instructions: Giriş və qoşulma axınlarını yoxla"
        }

    @Test
    fun `a second exploration while one runs is refused, and stopping it keeps what it learned`() =
        runBlocking<Unit> {
            val llm =
                az.petek.app.testing.PanelLlm().apply {
                    explorerPass = 1
                    explorerGate = CompletableDeferred()
                }
            val panel = harness(llm = llm)
            val instructions = PanelHarness.instructions(panel.site.base.toString())

            panel.backend.startExploration(instructions)
            panel.exploration { it.visited.isNotEmpty() }
            shouldThrow<PanelConflictException> { panel.backend.startExploration(instructions) }

            panel.backend.cancelExploration() shouldBe true
            val stopped = panel.exploration { it.status == ExplorationStatus.CANCELLED && it.model.pages.isNotEmpty() }

            stopped.message shouldBe "Dayandırıldı; öyrənilənlər saxlanıldı."
            stopped.visited.size shouldBe 1
            val stored =
                panel.panel.container.explorations
                    .list(limit = 1)
                    .single()
            stored.status.name shouldBe "CANCELLED"
            stored.modelVersion shouldBe 1
            panel.backend.cancelExploration() shouldBe false
            eventually(5.seconds) { panel.site.stopCount shouldBe 1 }
        }

    @Test
    fun `an answer is stored, shown with the instructions and grounds the next exploration, also after a restart`() =
        runBlocking<Unit> {
            val first = harness()
            val view = first.explored()
            val question = view.unknowns.single()

            val answered = first.backend.answerUnknown(question.id, "Admin şirkət səhifəsində görür")

            answered.unknowns.single().answer shouldBe "Admin şirkət səhifəsində görür"
            answered.instructions shouldContain "Cavab: Admin şirkət səhifəsində görür"
            shouldThrow<PanelNotFoundException> { first.backend.answerUnknown("u99", "x") }

            val second = first.explored()
            second.unknowns.single().answer shouldBe "Admin şirkət səhifəsində görür"
            first.llm.explorerPrompts.last() shouldContain "Cavab: Admin şirkət səhifəsində görür"
            first.close()
            open.remove(first)

            val restarted = harness()
            val shown = restarted.exploration { it.id == second.id && it.draftYaml != null }
            shown.status shouldBe ExplorationStatus.FINISHED
            shown.unknowns.single().answer shouldBe "Admin şirkət səhifəsində görür"
        }

    @Test
    fun `comparing with the previous exploration shows what changed on the site`() =
        runBlocking<Unit> {
            val panel = harness()
            panel.explored()
            panel.backend.compareWithPrevious().shouldBeNull()

            panel.site.remove("/join")
            panel.site.page("/", "Portal") {
                link("Daxil ol", "/login")
                link("Kömək", "/help")
            }
            panel.site.page("/help", "Kömək")
            panel.explored()

            val diff = panel.backend.compareWithPrevious().shouldNotBeNull()
            diff.fromVersion shouldBe 1
            diff.toVersion shouldBe 2
            diff.changes.map { it.kind to it.name } shouldContainAll
                listOf(ModelChangeKind.ADDED to "/help", ModelChangeKind.REMOVED to "/join")
        }

    @Test
    fun `with logged-in sessions the explorer walks as each role and touches create forms on a confirmed test target`() =
        runBlocking<Unit> {
            lateinit var panel: PanelHarness
            val sessions =
                RoleSessionSource { _, factory, progress ->
                    progress("Rol sessiyaları hazırdır: admin")
                    RoleSessions(
                        sessions = mapOf("admin" to factory.open(SessionOptions("admin", panel.site.base))),
                        testCheck = { TestTargetVerdict.Confirmed("company c1 is_test=true") },
                        note = null,
                    )
                }
            panel = harness(roleSessions = sessions)
            panel.site.page("/", "Portal", view = "admin") { link("Elanlar", "/announcements") }
            panel.site.page("/announcements", "Elanlar", view = "admin") {
                form("/announcements") {
                    field("Başlıq", "title", testId = "announcement-title")
                    submit("Dərc et", "announcement-submit")
                }
            }

            val view = panel.explored(PanelHarness.instructions(panel.site.base.toString(), allowWrites = true))

            view.phases.map { it.state } shouldBe listOf(PhaseState.DONE, PhaseState.DONE, PhaseState.DONE)
            view.phases[1].roles shouldBe listOf("admin")
            view.visited.any { it.visitedAs == "admin" && it.url.endsWith("/announcements") } shouldBe true
            val create =
                view.model.pages
                    .single { it.urlPattern == "/announcements" }
                    .actions
                    .single()
            create.kind shouldBe "CREATE"
            create.allowedRoles shouldBe listOf("admin")
            view.activity.map { it.text } shouldContain "Rol sessiyaları hazırdır: admin"
            view.activity.map { it.text } shouldContain "Sınaq toxunuşuna icazə verildi: company c1 is_test=true"
            panel.site.sessions
                .single { it.view == "admin" }
                .recorder.actions
                .any { it.startsWith("clickSelector [data-testid=\"announcement-submit\"]") } shouldBe true
        }

    @Test
    fun `on a site whose ownership is not proved the explorer only reads anonymously and says how to prove it`() =
        runBlocking<Unit> {
            val sessions =
                RoleSessionSource { _, _, _ -> error("no logged-in session may open on an unproved site") }
            // A public stage host: loopback would be exempt in production, so it could not show the read-only path.
            val panel =
                harness(
                    roleSessions = sessions,
                    ownership = OwnershipTestKit.unowned(FakeHarnessClock()),
                    site = URI("https://stage.example.com"),
                )
            panel.site.page("/", "Portal") { link("Qoşul", "/join") }
            panel.site.page("/join", "Qoşul")

            // Waits for the end of the exploration itself: a read-only walk need not produce a draft.
            val view = panel.ended(PanelHarness.instructions(panel.site.base.toString(), allowWrites = true))

            view.status shouldBe ExplorationStatus.FINISHED
            view.visited.shouldNotBeEmpty()
            view.visited.map { it.visitedAs }.distinct() shouldBe listOf("anonymous")
            view.phases.single { it.phase == ExplorationPhase.ANONYMOUS }.state shouldBe PhaseState.DONE
            view.phases.single { it.phase == ExplorationPhase.ROLE_BASED }.state shouldBe PhaseState.SKIPPED
            view.phases.single { it.phase == ExplorationPhase.TRIAL_TOUCH }.state shouldBe PhaseState.SKIPPED
            val note =
                view.activity.map { it.text }.single { it.startsWith("Sahiblik təsdiqlənmədiyi üçün kəşfiyyat yalnız anonim oxuyur") }
            note shouldContain "https://stage.example.com/.well-known/petek-verification.txt"
            note shouldContain "_petek-verification.stage.example.com"
        }
}
