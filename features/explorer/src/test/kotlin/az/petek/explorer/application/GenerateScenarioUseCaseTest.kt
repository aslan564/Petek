package az.petek.explorer.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.DefaultTemplateRenderer
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.model.Role
import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRecord
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.TestPattern
import az.petek.explorer.support.Models
import az.petek.explorer.testing.InMemoryExplorationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GenerateScenarioUseCaseTest {
    @TempDir
    lateinit var dir: Path

    private val runFunctions =
        setOf("login", "verify_identity", "read_email_code", "register_owner", "seed_company", "register_and_login", "logout")
    private val validator = DefaultCampaignValidator(DefaultTemplateRenderer())
    private val repository = InMemoryExplorationRepository()
    private val clock = FakeHarnessClock()

    private fun useCase(knownRunFunctions: Set<String> = runFunctions) =
        GenerateScenarioUseCase(validator, DefaultTemplateRenderer(), knownRunFunctions, repository, clock, SequentialIdGenerator())

    private fun request(
        maxIdeas: Int = 8,
        testApi: Boolean = false,
        instructions: String? = null,
    ) = ScenarioRequest(ExplorationId("exp_1"), instructions, maxIdeas, testApi)

    private fun Campaign.step(id: String): ScenarioStep = steps.single { it.id == id }

    /** The YAML the owner gets, read back by the real campaign loader. */
    private fun reload(yaml: String): Campaign {
        val file = dir.resolve("draft-${System.nanoTime()}.yaml")
        Files.writeString(file, yaml)
        return YamlCampaignSource().load(file)
    }

    @Test
    fun `a draft from the kadro model validates and covers the top ideas with code-checkable steps`() {
        val composed = useCase().compose(Models.kadro(), request())

        val campaign = composed.campaign
        validator.validate(campaign, runFunctions).shouldBeEmpty()
        campaign.setup.map { it.id } shouldContainExactly listOf("owner_signup", "seed", "join")
        campaign.setup.map { (it.action as StepAction.Run).function } shouldContainExactly
            listOf("register_owner", "seed_company", "register_and_login")
        campaign.steps.map { it.id } shouldContainExactly
            listOf(
                "announcement-submit-happy",
                "announcement-submit-permission",
                "ticket-submit-happy",
                "ticket-approve-permission",
                "ticket-reject-permission",
                "announcement-submit-realtime",
                "ticket-submit-realtime",
            )
        composed.covered.map { it.idea.pattern to it.idea.actionId } shouldHaveSize 7
        composed.skipped
            .single()
            .idea.actionId shouldBe "login-submit"
        composed.skipped.single().reason shouldContain "setup run functions"
        campaign.settings.roles.total shouldBe campaign.settings.testers
        campaign.settings.name shouldBe "explorer-kadro-test-v1"
    }

    @Test
    fun `happy path of a create types a marker, checks it on screen and emits the created object with its id source`() {
        val campaign = useCase().compose(Models.kadro(), request()).campaign

        val announce = campaign.step("announcement-submit-happy")
        announce.actors.raw shouldBe "admin"
        (announce.action as StepAction.Do).instruction shouldContain "/announcements"
        (announce.action as StepAction.Do).instruction shouldContain "'Dərc et'"
        announce.emits!!.event shouldBe "announcements_created"
        announce.assertions shouldContainExactly listOf(AssertionSpec.VisibleText("Pətək yoxlaması announcement-submit", 10.seconds))
        val ticket = campaign.step("ticket-submit-happy")
        ticket.actors.raw shouldBe "employee[n=1]"
        ticket.emits!!.idSource shouldBe IdSource.UrlRegex("/tickets/([^/?#]+)")
    }

    @Test
    fun `realtime ideas wait for the creation and measure delivery to the roles that saw it live`() {
        val campaign = useCase().compose(Models.kadro(), request()).campaign

        val realtime = campaign.step("announcement-submit-realtime")
        realtime.actors.raw shouldBe "employee[*] | manager[*]"
        realtime.action shouldBe StepAction.None
        realtime.waitFor!!.event shouldBe "announcements_created"
        realtime.assertions shouldContainExactly
            listOf(
                AssertionSpec.VisibleText("Pətək yoxlaması announcement-submit", 10.seconds),
                AssertionSpec.LatencyMax(5000.milliseconds),
            )
        campaign.step("ticket-submit-realtime").actors.raw shouldBe "manager[*]"
    }

    @Test
    fun `permission ideas check the element is hidden and the server refuses, on the created object when needed`() {
        val campaign = useCase().compose(Models.kadro(), request()).campaign

        val announce = campaign.step("announcement-submit-permission")
        announce.actors.raw shouldBe "employee[n=1]"
        announce.assertions shouldContainExactly
            listOf(
                AssertionSpec.NotVisible(null, "[data-testid=\"announcement-submit\"]"),
                AssertionSpec.HttpStatus("/announcements", "POST", 403),
            )
        val approve = campaign.step("ticket-approve-permission")
        (approve.action as StepAction.Do).instruction shouldContain "/tickets/{event.tickets_created.id}"
        approve.assertions.last() shouldBe AssertionSpec.HttpStatus("/tickets/{event.tickets_created.id}/approve", "POST", 403)
        campaign.step("ticket-reject-permission").assertions shouldHaveSize 1
    }

    @Test
    fun `with every idea the draft also has a race, idempotency counting and reasons for what it skipped`() {
        val composed = useCase().compose(Models.kadro(), request(maxIdeas = 50))

        val race = composed.campaign.step("ticket-approve-race")
        race.parallel shouldBe true
        race.actors.raw shouldBe "manager[n=1] | manager[n=2]"
        race.actors.selectors.map { it.role } shouldContainExactly listOf(Role.MANAGER, Role.MANAGER)
        race.assertions shouldContainExactly listOf(AssertionSpec.OnlyOneSucceeds)
        composed.campaign
            .step("ticket-submit-idempotency")
            .assertions
            .single() shouldBe
            AssertionSpec.Count("[data-testid=\"ticket-item\"]:has-text(\"Pətək təkrar ticket-submit\")", 1)
        val skipped = composed.skipped.associate { (it.idea.actionId to it.idea.pattern) to it.reason }
        skipped.getValue("ticket-submit" to TestPattern.BOUNDARY) shouldContain "input rules"
        skipped.getValue("login-submit" to TestPattern.BOUNDARY) shouldContain "setup run functions"
        composed.covered.flatMap { it.stepIds }.toSet() shouldBe
            composed.campaign.steps
                .map { it.id }
                .toSet()
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `with a test API created ids come from the oracle and the backend is checked too`() {
        val campaign = useCase().compose(Models.kadro(), request(testApi = true)).campaign

        campaign.target.idSources["announcements_created"] shouldBe IdSource.OracleField("/test/announcements/latest?by={self.email}", "id")
        campaign.step("announcement-submit-happy").assertions.last() shouldBe
            AssertionSpec.Oracle("/test/announcements/{last_id}", null, null, "Pətək yoxlaması announcement-submit")
        campaign.step("ticket-submit-happy").emits!!.idSource shouldBe null
        validator.validate(campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `the written YAML is read back by the campaign loader as the same campaign`() {
        listOf(request(), request(maxIdeas = 50), request(maxIdeas = 50, testApi = true)).forEach { scenario ->
            val composed = useCase().compose(Models.kadro(), scenario)

            val reloaded = reload(composed.yaml)

            reloaded.settings shouldBe composed.campaign.settings
            reloaded.target shouldBe composed.campaign.target
            reloaded.setup shouldBe composed.campaign.setup
            reloaded.steps shouldBe composed.campaign.steps
            reloaded.sourceHash shouldBe composed.campaign.sourceHash
            validator.validate(reloaded, runFunctions).shouldBeEmpty()
        }
    }

    @Test
    fun `the yaml explains itself in comments`() {
        val yaml = useCase().compose(Models.kadro(), request()).yaml

        yaml shouldContain "# Draft generated by the Pətək explorer from site model v1 of https://kadro.test (exp_1)."
        yaml shouldContain "# covers PERMISSION of ticket-approve: steps ticket-submit-happy, ticket-approve-permission"
        yaml shouldContain "# skipped HAPPY_PATH of login-submit:"
    }

    @Test
    fun `instructions choose which ideas make the draft`() {
        val composed = useCase().compose(Models.kadro(), request(maxIdeas = 1, instructions = "müraciət göndər"))

        composed.covered
            .single()
            .idea.actionId shouldBe "ticket-submit"
    }

    @Test
    fun `site texts never become template placeholders or break the YAML`() {
        val model = Models.kadro()
        val tricky =
            model.copy(
                actions =
                    model.actions.map {
                        if (it.id == "announcement-submit") it.copy(name = "Dərc et {self.email} \"now\" # {last_id}") else it
                    },
            )

        val composed = useCase().compose(tricky, request())

        val instruction = (composed.campaign.step("announcement-submit-happy").action as StepAction.Do).instruction
        instruction shouldNotContain "{self.email}"
        instruction shouldContain "(self.email)"
        reload(composed.yaml).steps shouldBe composed.campaign.steps
    }

    @Test
    fun `ideas without a campaign role or without a creator are skipped with the reason`() {
        val model =
            Models.model(
                listOf(Models.page("/orders/{id}", reachableBy = setOf("buyer")), Models.page("/shop", reachableBy = setOf("buyer"))),
                listOf(
                    Models.action(
                        "order-approve",
                        ActionKind.APPROVE,
                        "/orders/{id}",
                        allowed = setOf("manager"),
                        forbidden = setOf("employee"),
                    ),
                    Models.action("cart-add", ActionKind.CREATE, "/shop", allowed = setOf("buyer")),
                ),
            )

        val composed = useCase().compose(model, request(maxIdeas = 50))

        val reasons = composed.skipped.map { it.reason }
        reasons.any { "no observed action creates such an object" in it } shouldBe true
        reasons.any { "no campaign role (admin, manager, employee) was seen using 'cart-add' (seen: buyer)" in it } shouldBe true
        composed.campaign.steps.shouldBeEmpty()
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `a draft that would not validate is refused instead of returned`() {
        val error =
            shouldThrow<ScenarioGenerationException> { useCase(knownRunFunctions = setOf("login")).compose(Models.kadro(), request()) }

        error.issues.map { it.message }.any { "unknown run function 'register_owner'" in it } shouldBe true
    }

    @Test
    fun `execute stores the draft and announces it in the exploration's event log`() =
        runTest {
            val model: SiteModel = Models.kadro()
            repository.create(
                ExplorationRecord(
                    ExplorationId("exp_1"),
                    ExplorationRequest(Models.TARGET, "müraciət göndər", ExplorationBudget(), setOf(ExplorationPhase.ANONYMOUS)),
                    ExplorationStatus.COMPLETED,
                    Models.AT,
                ),
            )
            repository.saveModel(model)
            val seen = CopyOnWriteArrayList<ExplorationEvent>()

            val draft = useCase().execute(ScenarioRequest(ExplorationId("exp_1"), maxIdeas = 1), { seen += it })

            draft.id shouldBe "drf_1"
            draft.modelVersion shouldBe 1
            draft.covered
                .single()
                .idea.actionId shouldBe "ticket-submit"
            repository.drafts(ExplorationId("exp_1")) shouldContainExactly listOf(draft)
            val ready = seen.single().shouldBeInstanceOf<ExplorationEvent.DraftReady>()
            ready.header.seq shouldBe 1
            ready.draftId shouldBe draft.id
            repository.events(ExplorationId("exp_1")) shouldContain ready
            shouldThrow<IllegalArgumentException> { useCase().execute(ScenarioRequest(ExplorationId("exp_9"))) }
        }
}
