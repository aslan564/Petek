package az.petek.campaign.domain

import az.petek.campaign.testing.KNOWN_RUN_FUNCTIONS
import az.petek.campaign.testing.campaign
import az.petek.campaign.testing.settings
import az.petek.campaign.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DefaultCampaignValidatorTest {
    private val validator = DefaultCampaignValidator()

    private fun issues(campaign: Campaign): List<ValidationIssue> = validator.validate(campaign, KNOWN_RUN_FUNCTIONS)

    /** The single issue of [campaign] whose message contains [fragment]. */
    private fun issue(
        campaign: Campaign,
        fragment: String,
    ): ValidationIssue {
        val all = issues(campaign)
        val matching = all.filter { fragment in it.message }
        check(matching.size == 1) { "expected one issue containing '$fragment', got:\n${all.joinToString("\n")}" }
        return matching.single()
    }

    private val announce = step("announce", emits = "announcement_created", line = 20)

    @Test
    fun `a well-formed campaign has no issues`() {
        val campaign =
            campaign(
                announce,
                step(
                    "read",
                    actor = "employee[*]",
                    waitFor = "announcement_created",
                    assertions =
                        listOf(
                            AssertionSpec.VisibleText("Sabah 10:00", 5.seconds),
                            AssertionSpec.LatencyMax(5000.milliseconds),
                            AssertionSpec.Oracle("/test/announcements/{last_id}/receipts", null, null, "{self.email}"),
                        ),
                ),
                setup = listOf(step("seed", action = StepAction.Run("seed_company"), phase = StepPhase.SETUP)),
            )
        issues(campaign).shouldBeEmpty()
    }

    @Nested
    inner class Settings {
        private val lines =
            SourceLines(
                mapOf(
                    SourceLines.ROOT to 1,
                    "campaign" to 1,
                    "campaign.testers" to 3,
                    "campaign.roles" to 4,
                    "campaign.departments" to 5,
                    "campaign.departments[1]" to 6,
                    "campaign.names" to 7,
                    "campaign.names[1]" to 8,
                    "campaign.budget" to 9,
                    "campaign.budget.max_minutes" to 10,
                    "campaign.target" to 11,
                ),
            )

        private fun with(settings: CampaignSettings) = campaign(announce, settings = settings, sourceLines = lines)

        @Test
        fun `testers must be positive`() {
            val issue =
                issue(
                    with(settings(testers = 0, roles = RoleQuota(0, 0, 0), registration = RegistrationQuota(0, 0))),
                    "campaign.testers must",
                )
            issue.line shouldBe 3
            issue.message shouldContain "campaign.testers must be positive, was 0"
        }

        @Test
        fun `roles must add up to testers`() {
            val issue = issue(with(settings(testers = 11)), "roles add up")
            issue.line shouldBe 4
            issue.message shouldContain "roles add up to 10 (admin 1 + manager 3 + employee 6) but campaign.testers is 11"
        }

        @Test
        fun `role counts must not be negative`() {
            val roles = RoleQuota(admin = 2, manager = -1, employee = 0)
            val campaign = with(settings(testers = 1, roles = roles, registration = RegistrationQuota(0, -1)))
            issue(campaign, "campaign.roles.manager must not be negative").line shouldBe 4
        }

        @Test
        fun `registration must add up to the non-admin testers`() {
            val issue = issue(with(settings(registration = RegistrationQuota(invite = 5, companyCode = 5))), "registration adds up")
            issue.line shouldBe 1
            issue.message shouldContain
                "registration adds up to 10 (invite 5 + company_code 5) but there are 9 non-admin testers (manager 3 + employee 6)"
        }

        @Test
        fun `registration counts must not be negative`() {
            issue(with(settings(registration = RegistrationQuota(invite = 10, companyCode = -1))), "company_code must not be negative")
            issue(with(settings(registration = RegistrationQuota(invite = -1, companyCode = 10))), "invite must not be negative")
        }

        @Test
        fun `departments must not be empty`() {
            val campaign = with(settings(departments = emptyList()))
            issue(campaign, "must list at least one department").line shouldBe 5
        }

        @Test
        fun `departments must be unique ignoring case`() {
            val issue = issue(with(settings(departments = listOf("IT", "it"))), "listed twice")
            issue.line shouldBe 6
            issue.message shouldContain "department 'it' is listed twice"
        }

        @Test
        fun `departments must be addressable by the actor grammar`() {
            issue(with(settings(departments = listOf("IT", "R&D, Labs"))), "cannot name it").line shouldBe 6
            issue(with(settings(departments = listOf("IT", " "))), "is blank").line shouldBe 6
        }

        @Test
        fun `names must be unique ignoring case`() {
            val issue = issue(with(settings(names = listOf("Əli", "əli"))), "listed twice")
            issue.line shouldBe 8
            issue.message shouldContain "name 'əli' is listed twice"
        }

        @Test
        fun `names must not be blank`() {
            issue(with(settings(names = listOf("Əli", ""))), "campaign.names[1] is blank").line shouldBe 8
        }

        @Test
        fun `the budget must be positive`() {
            val campaign = with(settings(budget = Budget(maxStepsPerAgent = 0, maxMinutes = -5)))
            issue(campaign, "max_steps_per_agent must be positive, was 0").line shouldBe 9
            issue(campaign, "max_minutes must be positive, was -5").line shouldBe 10
        }

        @Test
        fun `the target must be an absolute http URL with a host`() {
            issue(with(settings(target = URI("staging.kadrohr.com"))), "campaign.target").line shouldBe 11
            issue(with(settings(target = URI("ftp://staging.kadrohr.com"))), "campaign.target")
            issue(with(settings(target = URI("https:///path-only"))), "campaign.target")
        }

        @Test
        fun `the target must not carry credentials, and messages never show them`() {
            val issue = issue(with(settings(target = URI("https://bob:hunter2@staging.kadrohr.test/app"))), "must not contain credentials")
            issue.line shouldBe 11
            issue.message shouldContain "'https://***@staging.kadrohr.test/app'"
            issue.message shouldNotContain "hunter2"

            val registry = issues(with(settings(target = URI("https://bob:hunter2@bad_host.test"))))
            registry.map { it.message }.forEach { it shouldNotContain "hunter2" }
            registry shouldHaveSize 2
        }

        @Test
        fun `the name must not be blank`() {
            issue(with(settings().copy(name = " ")), "campaign.name must not be blank")
        }

        @Test
        fun `settings issues have no line in a campaign built in code`() {
            issue(campaign(announce, settings = settings(testers = 11)), "roles add up").line shouldBe null
        }
    }

    @Nested
    inner class Steps {
        @Test
        fun `a campaign needs at least one step`() {
            issue(campaign(), "has no setup or steps")
        }

        @Test
        fun `step ids must be unique across setup and steps`() {
            val campaign =
                campaign(
                    step("same", line = 30),
                    setup = listOf(step("same", phase = StepPhase.SETUP, line = 12)),
                )
            val issue = issue(campaign, "used twice")
            issue.line shouldBe 30
            issue.message shouldContain "step id 'same' is used twice (first used at line 12)"
        }

        @Test
        fun `step ids must not be blank`() {
            issue(campaign(step(" ")), "step id must not be blank")
        }

        @Test
        fun `do text must not be blank`() {
            issue(campaign(step("s", action = StepAction.Do("  "), line = 44)), "do must not be blank").line shouldBe 44
        }

        @Test
        fun `run functions must be known`() {
            val issue = issue(campaign(step("s", action = StepAction.Run("delete_everything"), line = 8)), "unknown run function")
            issue.line shouldBe 8
            issue.message shouldContain "'delete_everything'"
            issue.message shouldContain "known: login, read_email_code, register_and_login"
        }

        @Test
        fun `run needs a function name`() {
            issue(campaign(step("s", action = StepAction.Run(" "))), "run needs a function name")
        }

        @Test
        fun `a step without do or run must wait or assert`() {
            issue(campaign(step("idle", action = StepAction.None)), "has neither do nor run")
            val waits = step("waits", action = StepAction.None, waitFor = "announcement_created")
            val asserts = step("asserts", action = StepAction.None, assertions = listOf(AssertionSpec.Count("li", 1)))
            issues(campaign(announce, waits, asserts)).shouldBeEmpty()
        }

        @Test
        fun `wait_for must name an event emitted by an earlier step`() {
            val waiter = step("read", actor = "employee", waitFor = "announcement_created", line = 50)
            issues(campaign(announce, waiter)).shouldBeEmpty()

            val later = issue(campaign(waiter, announce), "waits for 'announcement_created'")
            later.line shouldBe 50
            later.message shouldContain "emitted only by this or a later step"

            val self = step("self", waitFor = "loop", emits = "loop")
            issue(campaign(self), "waits for 'loop', which is emitted only by this or a later step")

            issue(campaign(announce, step("x", waitFor = "ticket_created")), "waits for 'ticket_created', which no step emits")
        }

        @Test
        fun `setup events count as earlier than main steps`() {
            val campaign =
                campaign(
                    step("read", actor = "employee", waitFor = "company_ready"),
                    setup = listOf(step("seed", action = StepAction.Run("seed_company"), emits = "company_ready", phase = StepPhase.SETUP)),
                )
            issues(campaign).shouldBeEmpty()
        }

        @Test
        fun `wait_for timeout must be positive and finite`() {
            val waiter = step("read", actor = "employee", waitFor = "announcement_created", waitTimeout = Duration.ZERO)
            issue(campaign(announce, waiter), "wait_for timeout must be positive")
            val forever = step("read", actor = "employee", waitFor = "announcement_created", waitTimeout = Duration.INFINITE)
            issue(campaign(announce, forever), "wait_for timeout must be finite, was Infinity")
        }

        @Test
        fun `emits needs an event name`() {
            issue(campaign(step("s", emits = " ")), "emits needs an event name")
        }

        @Test
        fun `issues of a code-built campaign use the step line`() {
            issue(campaign(step("s", action = StepAction.Run("nope"), line = 77)), "unknown run function").line shouldBe 77
        }

        @Test
        fun `issues of a loaded campaign use the most specific recorded line`() {
            val lines = SourceLines(mapOf("steps[0]" to 60, "steps[0].run" to 62))
            val campaign = campaign(step("s", action = StepAction.Run("nope"), line = 60), sourceLines = lines)
            issue(campaign, "unknown run function").line shouldBe 62
        }
    }

    @Nested
    inner class Actors {
        @Test
        fun `a department must be one of the campaign departments`() {
            val issue = issue(campaign(step("s", actor = "manager[Finance]", line = 9)), "names department 'Finance'")
            issue.line shouldBe 9
            issue.message shouldContain "which is not in campaign.departments (IT, HR)"
        }

        @Test
        fun `a department with the wrong case gets a hint`() {
            issue(campaign(step("s", actor = "employee[dept=it]")), "names department 'it'").message shouldContain "did you mean 'IT'?"
        }

        @Test
        fun `an actor that can never match a tester is reported`() {
            fun never(actor: String) = issue(campaign(step("s", actor = actor)), "can never match a tester")

            never("admin[IT]")
            never("admin[reg=invite]")
            never("employee[n=7]")
        }

        @Test
        fun `registration and position bounds use the quotas`() {
            val noCodes =
                settings(registration = RegistrationQuota(invite = 9, companyCode = 0))
            issue(campaign(step("s", actor = "employee[reg=company_code]"), settings = noCodes), "can never match a tester")
            issues(campaign(step("s", actor = "employee[reg=invite, n=6]"), settings = noCodes)).shouldBeEmpty()
            issue(campaign(step("s", actor = "employee[reg=invite, n=7]"), settings = noCodes), "can never match a tester")
        }

        @Test
        fun `an expression without selectors is reported`() {
            val empty = step("s").copy(actors = ActorExpression(emptyList(), ""))
            issue(campaign(empty), "the actor expression is empty")
        }

        @Test
        fun `a role with a zero quota cannot act`() {
            val noManagers = settings(roles = RoleQuota(1, 0, 9))
            issue(campaign(step("s", actor = "manager"), settings = noManagers), "can never match a tester")
            issues(campaign(step("s", actor = "manager | employee"), settings = noManagers)).shouldBeEmpty()
        }
    }

    @Nested
    inner class Assertions {
        private fun asserting(
            vararg assertions: AssertionSpec,
            actor: String = "employee",
            parallel: Boolean = false,
            waitFor: String? = null,
            action: StepAction = StepAction.Do("Elan yarat"),
        ) = campaign(
            announce,
            step(
                "check",
                actor = actor,
                action = action,
                waitFor = waitFor,
                parallel = parallel,
                assertions = assertions.toList(),
                line = 70,
            ),
        )

        @Test
        fun `latency_max needs an earlier visible_text in the same step`() {
            val waiting = "announcement_created"
            issue(asserting(AssertionSpec.LatencyMax(5.seconds), waitFor = waiting), "must come after a visible_text").line shouldBe 70
            issue(
                asserting(AssertionSpec.LatencyMax(5.seconds), AssertionSpec.VisibleText("x", 5.seconds), waitFor = waiting),
                "must come after a visible_text",
            )
            issues(
                asserting(AssertionSpec.VisibleText("x", 5.seconds), AssertionSpec.LatencyMax(5.seconds), waitFor = waiting),
            ).shouldBeEmpty()
        }

        @Test
        fun `latency_max needs a wait_for because latency is measured from the awaited event`() {
            val issue =
                issue(
                    asserting(AssertionSpec.VisibleText("x", 5.seconds), AssertionSpec.LatencyMax(5.seconds)),
                    "needs the step to wait_for an event",
                )
            issue.line shouldBe 70
            issue.message shouldContain "step 'check', latency_max"
        }

        @Test
        fun `latency_max must be positive and finite`() {
            fun latency(max: Duration) =
                asserting(AssertionSpec.VisibleText("x", 5.seconds), AssertionSpec.LatencyMax(max), waitFor = "announcement_created")

            issue(latency(Duration.ZERO), "ms must be positive")
            issue(latency(Duration.INFINITE), "ms must be finite")
        }

        @Test
        fun `only_one_succeeds needs parallel actors`() {
            val issue = issue(asserting(AssertionSpec.OnlyOneSucceeds, actor = "manager[IT] | manager[HR]"), "needs parallel: true")
            issue.message shouldContain "step 'check', only_one_succeeds"
        }

        @Test
        fun `only_one_succeeds needs actors that can match two testers`() {
            fun racing(actor: String) = asserting(AssertionSpec.OnlyOneSucceeds, actor = actor, parallel = true)

            issues(racing("manager[IT] | manager[HR]")).shouldBeEmpty()
            issues(racing("employee[n=1] | employee[n=2]")).shouldBeEmpty()
            issues(racing("manager[IT]")).shouldBeEmpty()
            issue(racing("admin"), "matches at most 1")
            issue(racing("admin | admin"), "matches at most 1")
            issue(racing("employee[dept=IT, n=1]"), "matches at most 1")
            issue(racing("employee[n=1] | employee[n=1]"), "matches at most 1")
        }

        @Test
        fun `only_one_succeeds needs an action whose outcomes are compared`() {
            val waiting =
                asserting(
                    AssertionSpec.OnlyOneSucceeds,
                    actor = "manager[IT] | manager[HR]",
                    parallel = true,
                    action = StepAction.None,
                )
            issue(waiting, "needs a do or run").message shouldContain "step 'check', only_one_succeeds"
            val running =
                asserting(
                    AssertionSpec.OnlyOneSucceeds,
                    actor = "manager[IT] | manager[HR]",
                    parallel = true,
                    action = StepAction.Run("login"),
                )
            issues(running).shouldBeEmpty()
        }

        @Test
        fun `visible_text needs text and a positive, finite wait`() {
            issue(asserting(AssertionSpec.VisibleText(" ", 5.seconds)), "text must not be blank")
            issue(asserting(AssertionSpec.VisibleText("x", Duration.ZERO)), "within_s must be positive")
            issue(asserting(AssertionSpec.VisibleText("x", Duration.INFINITE)), "within_s must be finite")
        }

        @Test
        fun `not_visible needs exactly one of text and selector`() {
            issue(asserting(AssertionSpec.NotVisible(null, null)), "exactly one of text or selector")
            issue(asserting(AssertionSpec.NotVisible("Approve", "#approve")), "exactly one of text or selector")
            issues(asserting(AssertionSpec.NotVisible("Approve", null))).shouldBeEmpty()
        }

        @Test
        fun `oracle needs a path`() {
            issue(asserting(AssertionSpec.Oracle("", "status", "ok", null)), "path must not be blank")
        }

        @Test
        fun `oracle and http_status paths must stay on the target`() {
            for (path in listOf("https://evil.test/test/x", "//evil.test/x", "/\\evil.test/x", "test/x", "{last_id}")) {
                issue(asserting(AssertionSpec.Oracle(path, null, null, null)), "starting with a single '/'").message shouldContain
                    "step 'check', oracle: path must be a path on the target"
                issue(asserting(AssertionSpec.HttpStatus(path, "GET", 200)), "starting with a single '/'").message shouldContain
                    "step 'check', http_status: path must be a path on the target"
            }
            issues(asserting(AssertionSpec.HttpStatus("/api/tickets/1/approve?next=https://x.test", "POST", 403))).shouldBeEmpty()
        }

        @Test
        fun `http_status needs a known method and a real status`() {
            issue(asserting(AssertionSpec.HttpStatus("/api/x", "FETCH", 200)), "method 'FETCH'")
            issue(asserting(AssertionSpec.HttpStatus("/api/x", "POST", 42)), "must be an HTTP status")
            issue(asserting(AssertionSpec.HttpStatus(" ", "GET", 200)), "path must not be blank")
        }

        @Test
        fun `count needs a selector and a non-negative number`() {
            issue(asserting(AssertionSpec.Count("", 1)), "selector must not be blank")
            issue(asserting(AssertionSpec.Count("li", -1)), "must not be negative")
            issues(asserting(AssertionSpec.Count("li", 0))).shouldBeEmpty()
        }

        @Test
        fun `assertion issues point at the assertion line when known`() {
            val lines = SourceLines(mapOf("steps[1]" to 70, "steps[1].assert[0]" to 72))
            val campaign =
                campaign(
                    announce,
                    step("check", actor = "employee", assertions = listOf(AssertionSpec.LatencyMax(1.seconds)), line = 70),
                    sourceLines = lines,
                )
            issue(campaign, "must come after a visible_text").line shouldBe 72
        }
    }

    @Nested
    inner class Templates {
        @Test
        fun `unknown placeholders are rejected`() {
            val issue = issue(campaign(step("s", action = StepAction.Do("Open {page}"), line = 5)), "unknown placeholder {page}")
            issue.line shouldBe 5
            issue.message shouldContain "{last_id}, {self.email}, {self.name}"
        }

        @Test
        fun `the password cannot be templated from a campaign file`() {
            issue(campaign(step("s", action = StepAction.Do("Type {self.password}"))), "{self.password} is not available")
        }

        @Test
        fun `all campaign self fields are allowed`() {
            val text = Placeholder.CAMPAIGN_SELF_FIELDS.joinToString(" ") { "{self.$it}" }
            issues(campaign(step("s", action = StepAction.Do(text)))).shouldBeEmpty()
        }

        @Test
        fun `placeholder look-alikes in the wrong case are rejected`() {
            issue(campaign(step("s", action = StepAction.Do("Mail {Self.Email}"))), "'{Self.Email}' looks like a placeholder")
        }

        @Test
        fun `placeholder look-alikes the renderer would leave literal are rejected`() {
            val hyphen =
                step("open", action = StepAction.Do("Open /tickets/{event.ticket-created.id}"))
            issue(campaign(step("t", emits = "ticket-created"), hyphen), "'{event.ticket-created.id}' looks like a placeholder")
            issue(campaign(announce, step("s", action = StepAction.Do("Open { last_id }"))), "'{ last_id }' looks like a placeholder")
        }

        @Test
        fun `braces that cannot be placeholders are left alone`() {
            val text = "Yaz: {\"a\": 1}, \\d{3}, {bir iki}"
            issues(campaign(step("s", action = StepAction.Do(text)))).shouldBeEmpty()
        }

        @Test
        fun `last_id in an action needs an event from an earlier step`() {
            issue(
                campaign(step("s", action = StepAction.Do("Open ticket {last_id}"), emits = "ticket_created")),
                "{last_id} needs an event",
            )
            issues(campaign(announce, step("s", action = StepAction.Do("Open {last_id}")))).shouldBeEmpty()
        }

        @Test
        fun `assertions may use the id emitted by their own step`() {
            val oracle = AssertionSpec.Oracle("/test/announcements/{last_id}", "status", "published", null)
            val own = step("announce", emits = "announcement_created", assertions = listOf(oracle))
            issues(campaign(own)).shouldBeEmpty()
            issue(campaign(step("s", assertions = listOf(oracle))), "{last_id} needs an event emitted by this or an earlier step")
        }

        @Test
        fun `event placeholders need that event emitted before`() {
            val use = step("use", action = StepAction.Do("Open {event.ticket_created.id}"))
            issues(campaign(step("t", emits = "ticket_created"), use)).shouldBeEmpty()
            issue(campaign(use, step("t", emits = "ticket_created")), "which is not emitted by an earlier step")
            issue(campaign(use), "which no step emits")
        }

        @Test
        fun `run arguments are templates too`() {
            val run = StepAction.Run("login", mapOf("as" to "{self.email}", "ticket" to "{last_id}"))
            issue(campaign(step("s", action = run)), "run argument 'ticket': {last_id} needs an event")
        }

        @Test
        fun `every assertion text is checked`() {
            val assertions =
                listOf(
                    AssertionSpec.VisibleText("{a}", 5.seconds),
                    AssertionSpec.NotVisible(null, "{b}"),
                    AssertionSpec.Oracle("{c}", null, "{d}", "{e}"),
                    AssertionSpec.HttpStatus("{f}", "GET", 200),
                    AssertionSpec.Count("{g}", 1),
                )
            val found = issues(campaign(step("s", assertions = assertions))).filter { "unknown placeholder" in it.message }
            found shouldHaveSize 7
        }

        @Test
        fun `an id source of a step may only use events from earlier steps`() {
            val oracle = IdSource.OracleField("/test/tickets/{event.ticket_created.id}", "id")
            issue(campaign(step("t", emits = "ticket_created", idSource = oracle)), "emits.id_from: {event.ticket_created.id}")
        }
    }

    @Nested
    inner class IdSources {
        private val lines = SourceLines(mapOf(SourceLines.ROOT to 1, "target_profile.id_sources.ghost" to 14))

        private fun profile(vararg sources: Pair<String, IdSource>) = TargetProfile(emptyMap(), emptyMap(), mapOf(*sources))

        @Test
        fun `every configured id source must belong to an emitted event`() {
            val campaign = campaign(announce, target = profile("ghost" to IdSource.AgentReport), sourceLines = lines)
            val issue = issue(campaign, "no step emits 'ghost'")
            issue.line shouldBe 14
            issue.message shouldContain "target_profile.id_sources.ghost"
        }

        @Test
        fun `url_regex must compile and capture the id`() {
            issue(
                campaign(announce, target = profile("announcement_created" to IdSource.UrlRegex("/a/([0-9]+"))),
                "is not a valid regular expression",
            )
            issue(campaign(announce, target = profile("announcement_created" to IdSource.UrlRegex("/a/\\d{2,}"))), "needs a capture group")
            issues(campaign(announce, target = profile("announcement_created" to IdSource.UrlRegex("/a/(\\d{2,})")))).shouldBeEmpty()
        }

        @Test
        fun `oracle and dom sources need all their parts`() {
            issue(campaign(announce, target = profile("announcement_created" to IdSource.OracleField(" ", "id"))), "oracle path")
            issue(campaign(announce, target = profile("announcement_created" to IdSource.OracleField("/x", ""))), "oracle field")
            issue(campaign(announce, target = profile("announcement_created" to IdSource.DomAttribute("", "data-id"))), "dom selector")
            issue(campaign(announce, target = profile("announcement_created" to IdSource.DomAttribute("li", " "))), "dom attribute")
        }

        @Test
        fun `oracle id sources must stay on the target`() {
            val offsite = IdSource.OracleField("https://evil.test/latest?by={self.email}", "id")
            issue(campaign(announce, target = profile("announcement_created" to offsite)), "oracle path must be a path on the target")
        }

        @Test
        fun `page paths and selectors of the target profile are checked`() {
            val lines = SourceLines(mapOf(SourceLines.ROOT to 1, "target_profile.paths.login" to 17))
            val profile =
                TargetProfile(
                    paths = mapOf("login" to "https://evil.test/login", "ticket" to "/tickets/{last_id}", "home" to "/{Self.Home}"),
                    selectors = mapOf("login.email" to " ", "item" to "[data-id='{event.nope.id}']"),
                    idSources = emptyMap(),
                )
            val found = issues(campaign(announce, target = profile, sourceLines = lines))
            found.single { "target_profile.paths.login must be a path on the target" in it.message }.line shouldBe 17
            found.single { "'{Self.Home}' looks like a placeholder" in it.message }
            found.single { "target_profile.selectors.login.email must not be blank" in it.message }
            found.single { "{event.nope.id} refers to 'nope', which no step emits" in it.message }
            found shouldHaveSize 4
        }

        @Test
        fun `id source templates are checked`() {
            val oracle = IdSource.OracleField("/test/announcements/latest?by={self.password}", "id")
            issue(campaign(announce, target = profile("announcement_created" to oracle)), "{self.password} is not available")
            val fine = IdSource.OracleField("/test/announcements/latest?by={self.email}", "id")
            issues(campaign(announce, target = profile("announcement_created" to fine))).shouldBeEmpty()
        }
    }

    @Test
    fun `all issues are reported at once`() {
        val campaign =
            campaign(
                step("a", action = StepAction.Run("nope")),
                step("a", actor = "manager[Nowhere]", waitFor = "never"),
                settings = settings(testers = 12),
            )
        issues(campaign) shouldHaveSize 5
    }
}
