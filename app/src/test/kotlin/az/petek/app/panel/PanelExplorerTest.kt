package az.petek.app.panel

import az.petek.app.panel.explorer.RoleSessionSource
import az.petek.app.panel.explorer.RoleSessions
import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelWaits
import az.petek.app.testing.PanelWaits.exploration
import az.petek.app.testing.PanelWaits.explored
import az.petek.browser.domain.SessionOptions
import az.petek.core.ids.ArtifactId
import az.petek.dashboard.domain.ExplorationPhase
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.ModelChangeKind
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PhaseState
import az.petek.explorer.domain.TestTargetVerdict
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
    ): PanelHarness =
        PanelHarness(
            dir,
            site = PanelWaits.site(),
            llm = llm,
            allowProduction = allowProduction,
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
                shouldThrow<PanelRequestException> { panel.backend.startExploration(PanelHarness.instructions("https://KadroHR.com/")) }

            refusal.problems.single().field shouldBe PanelInstructions.TARGET
            refusal.problems.single().message shouldContain "'kadrohr.com' istehsal ünvanıdır"
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
            panel.site.page("/", "Kadro") {
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
            panel.site.page("/", "Kadro", view = "admin") { link("Elanlar", "/announcements") }
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
}
