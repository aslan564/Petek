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

package az.petek.explorer.application

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.DefaultTemplateRenderer
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.Tenant
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
import io.kotest.matchers.collections.shouldNotContain
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
        setOf(
            "login",
            "verify_identity",
            "read_email_code",
            "register_owner",
            "seed_company",
            "register_and_login",
            "logout",
            "site_health",
            "page_checks",
            "direct_url",
        )
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
    fun `a draft for a site without companies signs its testers up, names the roles it saw and seeds nothing`() {
        val composed = useCase().compose(Models.portal(), request().copy(tenant = Tenant.NONE))

        val campaign = composed.campaign
        validator.validate(campaign, runFunctions).shouldBeEmpty()
        campaign.settings.tenant shouldBe Tenant.NONE
        campaign.settings.departments.shouldBeEmpty()
        // People sign in here: every tester checks the pages a visitor sees before the gates.
        campaign.setup.map { (it.action as StepAction.Run).function } shouldContainExactly
            listOf("site_health", "page_checks", "register_and_login")
        campaign.allSteps.none { (it.action as? StepAction.Run)?.function in setOf("register_owner", "seed_company") } shouldBe true
        // The portal model shows a sign-in but no sign-up form: its testers take the owner's accounts.
        campaign.settings.registration.login shouldBe campaign.settings.testers
        composed.yaml shouldContain "gate blocker: no_sign_up"
        val reloaded = reload(composed.yaml)
        reloaded.settings.tenant shouldBe Tenant.NONE
        reloaded.settings.roles shouldBe campaign.settings.roles
        validator.validate(reloaded, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `a site without sign-in gets the owner's count of visitors, and every one checks every page`() {
        val site = Models.model(listOf(Models.page("/"), Models.page("/about")), emptyList(), roles = listOf("anonymous"))

        val composed = useCase().compose(site, request().copy(tenant = Tenant.NONE, testers = 30))

        val campaign = composed.campaign
        validator.validate(campaign, runFunctions).shouldBeEmpty()
        campaign.settings.testers shouldBe 30
        campaign.settings.registration.guest shouldBe 30
        campaign.setup.map { it.id } shouldContainExactly listOf("gates")
        campaign.steps.map { it.id } shouldContainExactly listOf("site-pages", "site-content")
        campaign.steps.forEach { step ->
            step.actors.raw shouldBe "anonymous[*]"
            val args = (step.action as StepAction.Run).args
            args["pages"] shouldBe "/,/about"
            args["share"] shouldBe "work"
            args["devices"] shouldBe "phone,tablet,desktop"
        }
        (campaign.step("site-pages").action as StepAction.Run).args["checks"] shouldBe "console,slow,links,back,mobile"
        (campaign.step("site-content").action as StepAction.Run).args["checks"] shouldBe "anchors,images,alt,meta,outbound"
        composed.skipped.shouldBeEmpty()
    }

    @Test
    fun `where people sign in the visitor pages are checked before the gates and each role's own pages after the scenario`() {
        val campaign = useCase().compose(Models.portal(), request()).campaign

        campaign.setup.takeWhile { it.id != "owner_signup" }.forEach { step ->
            step.actors.raw shouldBe "admin | manager[*] | employee[*]"
            (step.action as StepAction.Run).args["share"] shouldBe "work"
        }
        val roleChecks = campaign.steps.filter { it.id.endsWith("-pages") || it.id.endsWith("-content") }
        roleChecks.map { it.id }.first() shouldBe "admin-pages"
        (roleChecks.first().action as StepAction.Run).args["checks"] shouldContain "session"
        campaign.steps.takeLast(roleChecks.size) shouldBe roleChecks
    }

    @Test
    fun `a draft from the portal model validates and covers the top ideas with code-checkable steps`() {
        val composed = useCase().compose(Models.portal(), request())

        val campaign = composed.campaign
        validator.validate(campaign, runFunctions).shouldBeEmpty()
        campaign.setup.map { it.id } shouldContainExactly listOf("public-pages", "public-content", "owner_signup", "seed", "join")
        campaign.setup.map { (it.action as StepAction.Run).function } shouldContainExactly
            listOf("site_health", "page_checks", "register_owner", "seed_company", "register_and_login")
        campaign.steps.map { it.id }.filterNot { it.endsWith("-pages") || it.endsWith("-content") } shouldContainExactly
            listOf(
                "announcement-submit-watch",
                "announcement-submit-happy",
                "announcement-submit-realtime",
                "announcement-submit-permission",
                "ticket-submit-watch",
                "ticket-submit-happy",
                "ticket-submit-realtime",
                "ticket-approve-permission",
                "ticket-reject-permission",
            )
        composed.covered.filterNot { it.idea.pattern.siteWide } shouldHaveSize 7
        val skipped = composed.skipped.filterNot { it.idea.pattern.siteWide }
        skipped.single().idea.actionId shouldBe "login-submit"
        skipped.single().reason shouldContain "setup run functions"
        campaign.settings.roles.total shouldBe campaign.settings.testers
        // One name per site, so every exploration's draft becomes the next version of the same scenario.
        campaign.settings.name shouldBe "explorer-portal-test"
    }

    @Test
    fun `happy path of a create types a marker, checks it on screen and emits the created object with its id source`() {
        val campaign = useCase().compose(Models.portal(), request()).campaign

        val announce = campaign.step("announcement-submit-happy")
        announce.actors.raw shouldBe "admin"
        (announce.action as StepAction.Do).instruction shouldContain "/announcements"
        (announce.action as StepAction.Do).instruction shouldContain "'Dərc et'"
        announce.emits!!.event shouldBe "announcements_created"
        // The form's request is the write its readers' delivery is measured from (Faza 24.10).
        announce.emits!!.request shouldBe RequestPattern("POST", "/announcements")
        announce.assertions shouldContainExactly listOf(AssertionSpec.VisibleText("Pətək yoxlaması announcement-submit", 10.seconds))
        val ticket = campaign.step("ticket-submit-happy")
        ticket.actors.raw shouldBe "employee[n=1]"
        ticket.emits!!.idSource shouldBe IdSource.UrlRegex("/tickets/([^/?#]+)")
        ticket.emits!!.request shouldBe RequestPattern("POST", "/tickets")
    }

    @Test
    fun `realtime ideas wait for the creation and measure delivery to the roles that saw it live`() {
        val campaign = useCase().compose(Models.portal(), request()).campaign

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
    fun `receivers open the page before the creation and are checked right after it, inside the visible_text window`() {
        // The runner runs steps in order and checks visible_text of a wait_for step against t0 + within: a check that
        // came after other steps would find its window over, and a receiver on another page could never see the item.
        val composed = useCase().compose(Models.portal(), request(maxIdeas = 50))
        val ids = composed.campaign.steps.map { it.id }

        listOf("announcement-submit", "ticket-submit").forEach { action ->
            val creator = ids.indexOf("$action-happy")
            ids[creator - 1] shouldBe "$action-watch"
            ids[creator + 1] shouldBe "$action-realtime"
            val watch = composed.campaign.step("$action-watch")
            watch.actors.raw shouldBe
                composed.campaign
                    .step("$action-realtime")
                    .actors.raw
            watch.waitFor shouldBe null
            watch.emits shouldBe null
        }
        (composed.campaign.step("announcement-submit-watch").action as StepAction.Do).instruction shouldContain "/announcements"
        composed.covered
            .single { it.idea.pattern == TestPattern.REALTIME && it.idea.actionId == "ticket-submit" }
            .stepIds shouldContainExactly listOf("ticket-submit-watch", "ticket-submit-happy", "ticket-submit-realtime")
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
        reload(composed.yaml).steps shouldBe composed.campaign.steps
    }

    @Test
    fun `permission ideas check the element is hidden and the server refuses, on the created object when needed`() {
        val campaign = useCase().compose(Models.portal(), request()).campaign

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
        val composed = useCase().compose(Models.portal(), request(maxIdeas = 50))

        val race = composed.campaign.step("ticket-approve-race")
        race.parallel shouldBe true
        race.actors.raw shouldBe "manager[n=1] | manager[n=2]"
        race.actors.selectors.map { it.role } shouldContainExactly listOf(Role.MANAGER, Role.MANAGER)
        race.assertions shouldContainExactly listOf(AssertionSpec.OnlyOneSucceeds(RequestPattern("POST", "/tickets/[^/]+/approve")))
        composed.campaign
            .step("ticket-submit-idempotency")
            .assertions
            .single() shouldBe
            AssertionSpec.Count("[data-testid=\"ticket-item\"]:has-text(\"Pətək təkrar ticket-submit\")", 1)
        val skipped = composed.skipped.associate { (it.idea.actionId to it.idea.pattern) to it.reason }
        skipped.getValue("ticket-submit" to TestPattern.BOUNDARY) shouldContain "input rules"
        skipped.getValue("login-submit" to TestPattern.BOUNDARY) shouldContain "setup run functions"
        composed.covered.flatMap { it.stepIds }.toSet() shouldBe
            (composed.campaign.steps.map { it.id } + listOf("public-pages", "public-content")).toSet()
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `a race whose request the explorer never saw is not written, since code could not decide the winner`() {
        val portal = Models.portal()
        val unseen =
            portal.copy(actions = portal.actions.map { if (it.id == "ticket-approve") it.copy(httpMethod = null, httpPath = null) else it })

        val composed = useCase().compose(unseen, request(maxIdeas = 50))

        composed.campaign.steps.map { it.id } shouldNotContain "ticket-approve-race"
        composed.skipped.single { it.idea.actionId == "ticket-approve" && it.idea.pattern == TestPattern.RACE }.reason shouldContain
            "was not seen, so code could not decide who won a race"
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `with a test API created ids come from the oracle and the backend is checked too`() {
        val campaign = useCase().compose(Models.portal(), request(testApi = true)).campaign

        campaign.target.idSources["announcements_created"] shouldBe IdSource.OracleField("/test/announcements/latest?by={self.email}", "id")
        campaign.step("announcement-submit-happy").assertions.last() shouldBe
            AssertionSpec.Oracle("/test/announcements/{last_id}", null, null, "Pətək yoxlaması announcement-submit")
        campaign.step("ticket-submit-happy").emits!!.idSource shouldBe null
        validator.validate(campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `a company draft takes the departments and roles the explorer saw, not the contract's`() {
        val portal = Models.portal()
        // The site's own departments, in its ticket form; no manager was ever seen signed in.
        val sales =
            portal.copy(
                pages =
                    portal.pages.map { page ->
                        page.copy(
                            forms =
                                page.forms.map { form ->
                                    form.copy(
                                        fields =
                                            form.fields.map { field ->
                                                if (field.name ==
                                                    "department"
                                                ) {
                                                    field.copy(options = listOf("Seçin", "Satış", "Anbar"))
                                                } else {
                                                    field
                                                }
                                            },
                                    )
                                },
                        )
                    },
                roles = portal.roles.filter { it.name != "manager" },
                actions =
                    portal.actions.map { action ->
                        action.copy(
                            allowedRoles = action.allowedRoles - "manager",
                            forbiddenRoles = action.forbiddenRoles - "manager",
                            trial = action.trial?.let { it.copy(seenLiveBy = it.seenLiveBy - "manager") },
                        )
                    },
            )

        val campaign = useCase().compose(sales, request(testApi = true)).campaign

        campaign.settings.departments shouldBe listOf("Satış", "Anbar")
        campaign.settings.roles.manager shouldBe 0
        campaign.settings.roles.employee shouldBe ScenarioSettings.FAN_OUT
        validator.validate(campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `a company draft whose explorer saw no department gets one of its own, and the owner's win`() {
        val portal = Models.portal()
        val noDepartments =
            portal.copy(
                pages =
                    portal.pages.map { page ->
                        page.copy(
                            forms =
                                page.forms.map { form ->
                                    form.copy(
                                        fields =
                                            form.fields.filter {
                                                it.name !=
                                                    "department"
                                            },
                                    )
                                },
                        )
                    },
            )

        useCase()
            .compose(noDepartments, request(testApi = true))
            .campaign.settings.departments shouldBe
            listOf(ScenarioSettings.OWN_DEPARTMENT)
        GenerateScenarioUseCase(
            validator,
            DefaultTemplateRenderer(),
            runFunctions,
            repository,
            clock,
            SequentialIdGenerator(),
            ScenarioSettings(departments = listOf("Maliyyə")),
        ).compose(noDepartments, request(testApi = true)).campaign.settings.departments shouldBe listOf("Maliyyə")
    }

    @Test
    fun `oracle checks follow what the test API served in the trial, whatever the resource is called`() {
        // A site whose test API serves notes, not the contract's announcements or tickets (Faza 25.2).
        val notes =
            Models.model(
                listOf(
                    Models.page("/login", Models.form(ActionKind.LOGIN, "login-submit", "/login", Models.field("email", "email"))),
                    Models.page("/notes", Models.form(ActionKind.CREATE, "note-submit", "/notes", Models.field("text", required = true))),
                    Models.page(
                        "/drafts",
                        Models.form(ActionKind.CREATE, "draft-submit", "/drafts", Models.field("text", required = true)),
                    ),
                ),
                listOf(
                    Models.action(
                        "note-submit",
                        ActionKind.CREATE,
                        "/notes",
                        httpPath = "/notes",
                        trial = Models.trial(emptySet(), testApi = true),
                    ),
                    Models.action(
                        "draft-submit",
                        ActionKind.CREATE,
                        "/drafts",
                        httpPath = "/drafts",
                        trial = Models.trial(emptySet(), testApi = false),
                    ),
                ),
            )

        val campaign = useCase().compose(notes, request(maxIdeas = 50, testApi = true)).campaign

        campaign.target.idSources shouldBe mapOf("notes_created" to IdSource.OracleField("/test/notes/latest?by={self.email}", "id"))
        campaign
            .step("note-submit-happy")
            .assertions
            .filterIsInstance<AssertionSpec.Oracle>()
            .single()
            .path shouldBe
            "/test/notes/{last_id}"
        // The test API did not answer with the draft the trial made: no oracle check is written for it.
        campaign.step("draft-submit-happy").assertions.none { it is AssertionSpec.Oracle } shouldBe true
    }

    @Test
    fun `the written YAML is read back by the campaign loader as the same campaign`() {
        listOf(request(), request(maxIdeas = 50), request(maxIdeas = 50, testApi = true)).forEach { scenario ->
            val composed = useCase().compose(Models.portal(), scenario)

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
        val yaml = useCase().compose(Models.portal(), request()).yaml

        yaml shouldContain "# Draft generated by the Pətək explorer from site model v1 of https://portal.test (exp_1)."
        yaml shouldContain "# covers PERMISSION of ticket-approve: steps ticket-submit-happy, ticket-approve-permission"
        yaml shouldContain "# skipped HAPPY_PATH of login-submit:"
    }

    @Test
    fun `instructions choose which ideas make the draft`() {
        val composed = useCase().compose(Models.portal(), request(maxIdeas = 1, instructions = "müraciət göndər"))

        composed.covered
            .filterNot { it.idea.pattern.siteWide }
            .single()
            .idea.actionId shouldBe "ticket-submit"
    }

    @Test
    fun `site texts never become template placeholders or break the YAML`() {
        val model = Models.portal()
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
        // Only the blind site-wide checks remain, which need no role difference and no creator.
        composed.campaign.steps
            .map { (it.action as StepAction.Run).function }
            .toSet() shouldBe setOf("site_health", "page_checks")
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `actions on nested object pages and selectors with braces are skipped, and the draft still validates`() {
        val portal = Models.portal()
        val braced =
            Models.action(
                "ticket-close",
                ActionKind.UPDATE,
                "/tickets/{id}",
                allowed = setOf("manager"),
                forbidden = setOf("employee"),
            )
        val model =
            portal.copy(
                pages = portal.pages + Models.page("/tickets/{id}/comments/{id}", reachableBy = setOf("manager")),
                actions =
                    portal.actions +
                        Models.action(
                            "comment-approve",
                            ActionKind.APPROVE,
                            "/tickets/{id}/comments/{id}",
                            allowed = setOf("manager"),
                            forbidden = setOf("employee"),
                        ) +
                        braced.copy(selector = "role=button[name=\"{{ close }}\"]"),
            )

        val composed = useCase().compose(model, request(maxIdeas = 50))

        val reasons = composed.skipped.associate { (it.idea.actionId to it.idea.pattern) to it.reason }
        reasons.getValue("comment-approve" to TestPattern.RACE) shouldContain "nested objects"
        reasons.getValue("comment-approve" to TestPattern.PERMISSION) shouldContain "nested objects"
        reasons.getValue("ticket-close" to TestPattern.PERMISSION) shouldContain "braces"
        composed.campaign.steps.none { "comment-approve" in it.id } shouldBe true
        composed.yaml
            .lines()
            .filterNot { it.startsWith("#") }
            .none { "{id}" in it } shouldBe true
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
        reload(composed.yaml).steps shouldBe composed.campaign.steps
    }

    @Test
    fun `a create form on an object page first creates that object and opens its page`() {
        val portal = Models.portal()
        val model =
            portal.copy(
                actions =
                    portal.actions +
                        Models.action(
                            "comment-add",
                            ActionKind.CREATE,
                            "/tickets/{id}",
                            name = "Şərh yaz",
                            allowed = setOf("manager"),
                            httpPath = "/tickets/{id}/comments",
                        ),
            )

        val composed = useCase().compose(model, request(maxIdeas = 50, instructions = "şərh"))

        val comment = composed.campaign.step("comment-add-happy")
        (comment.action as StepAction.Do).instruction shouldContain "/tickets/{event.tickets_created.id} səhifəsini aç"
        comment.emits!!.event shouldBe "comments_created"
        val ids = composed.campaign.steps.map { it.id }
        (ids.indexOf("ticket-submit-happy") < ids.indexOf("comment-add-happy")) shouldBe true
        composed.covered
            .single { it.idea.actionId == "comment-add" && it.idea.pattern == TestPattern.HAPPY_PATH }
            .stepIds shouldContainExactly listOf("ticket-submit-happy", "comment-add-happy")
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `with a test API only the resources the trial saw it serve get oracle ids and checks, others are named by their form`() {
        val portal = Models.portal()
        val model =
            portal.copy(
                pages =
                    portal.pages +
                        Models.page(
                            "/company",
                            Models.form(ActionKind.CREATE, "company-department-submit", "/company/departments", Models.field("name")),
                            reachableBy = setOf("admin"),
                        ),
                actions =
                    portal.actions +
                        Models.action(
                            "company-department-submit",
                            ActionKind.CREATE,
                            "/company",
                            name = "Əlavə et",
                            allowed = setOf("admin"),
                            httpPath = "/company/departments",
                        ),
            )

        val composed = useCase().compose(model, request(maxIdeas = 50, testApi = true, instructions = "departament"))

        val department = composed.campaign.step("company-department-submit-happy")
        department.emits!!.event shouldBe "departments_created"
        department.assertions.none { it is AssertionSpec.Oracle } shouldBe true
        composed.campaign.target.idSources.keys shouldBe setOf("announcements_created", "tickets_created")
        composed.campaign.target.idSources.values
            .map { (it as IdSource.OracleField).path }
            .none { "/test/company" in it || "/test/departments" in it } shouldBe true
        validator.validate(composed.campaign, runFunctions).shouldBeEmpty()
    }

    @Test
    fun `a draft that would not validate is refused instead of returned`() {
        val error =
            shouldThrow<ScenarioGenerationException> { useCase(knownRunFunctions = setOf("login")).compose(Models.portal(), request()) }

        error.issues.map { it.message }.any { "unknown run function 'register_owner'" in it } shouldBe true
    }

    @Test
    fun `execute stores the draft and announces it in the exploration's event log`() =
        runTest {
            val model: SiteModel = Models.portal()
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
                .filterNot { it.idea.pattern.siteWide }
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
