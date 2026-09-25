package az.petek.campaign.infrastructure

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.Flow
import az.petek.campaign.domain.FlowFailure
import az.petek.campaign.domain.FlowFailureReason
import az.petek.campaign.domain.FlowNames
import az.petek.campaign.domain.FlowStep
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.JourneyPage
import az.petek.campaign.domain.LinkPurpose
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.ValidationIssue
import az.petek.campaign.domain.ValueTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `target_profile.flows`, `local_storage`, `dismiss`, `api_prefix` and `campaign.pacing` in campaign files. */
class FlowYamlReadingTest {
    @TempDir
    lateinit var dir: Path

    private fun load(yaml: String): Campaign =
        YamlCampaignSource().load(dir.resolve("flows.yaml").also { Files.writeString(it, yaml.trimIndent()) })

    private fun issues(yaml: String): List<ValidationIssue> = shouldThrow<CampaignValidationException> { load(yaml) }.issues

    private fun issue(
        yaml: String,
        fragment: String,
    ): ValidationIssue {
        val all = issues(yaml)
        val matching = all.filter { fragment in it.message }
        check(matching.size == 1) { "expected one issue containing '$fragment', got:\n${all.joinToString("\n")}" }
        return matching.single()
    }

    /** Lines 1-8: a valid campaign that tests append `target_profile:` lines to (line 9 onwards). */
    private val header =
        """
        campaign:
          target: https://staging.kadrohr.test
          testers: 3
          seed: 7
          roles: {admin: 1, manager: 0, employee: 2}
          departments: [IT]
          budget: {max_steps_per_agent: 10, max_minutes: 5}
        steps: [{actor: admin, do: "Elan yarat"}]
        """.trimIndent()

    private fun profile(lines: String): String = header + "\n" + lines.trimIndent()

    private fun flow(
        name: String,
        steps: String,
    ): Flow {
        val indented = steps.trimIndent().lines().joinToString("\n") { "      $it" }
        return load(profile("target_profile:\n  flows:\n    $name:\n$indented")).target.flows.getValue(name)
    }

    @Test
    fun `every step kind is read in its long form`() {
        val login =
            flow(
                "login",
                """
                - goto: /login
                - fill: {selector: login.email, value: "{self.email}"}
                - select: {selector: "#reg-country", option: Azərbaycan}
                - check: {selector: "role=radio[name='Hibrid']"}
                - click: {selector: login.submit}
                - click_if_visible: {selector: "#skip"}
                - wait_for:
                    any: [session.user_name, login.error]
                    timeout_s: 2.5
                    fail: {reason: login_failed, message: "Login failed", error: login.error}
                - expect_url: {regex: "/home$", timeout_s: 30, fail: {message: "not home ({url})"}}
                - email_link: {purpose: verify, pattern: "verify\\?token=", open: false, into: shared.verify}
                - email_code: {selector: verify.code, submit: verify.submit}
                - phone_code: {selector: verify.phone_code, submit: verify.phone_submit}
                - read: {selector: "#code", into: shared.company_code, regex: "([A-Z0-9]{8})"}
                - set_shared: {key: plan, value: "free"}
                - if_visible: {selector: "#consent", timeout_s: 1, then: [{click: "#accept"}]}
                - save_session
                - account_created
                - assert_identity: {selector: session.user_name}
                """,
            )

        login.steps shouldContainExactly
            listOf(
                FlowStep.Goto("/login"),
                FlowStep.Fill("login.email", "{self.email}"),
                FlowStep.Select("#reg-country", "Azərbaycan"),
                FlowStep.Check("role=radio[name='Hibrid']"),
                FlowStep.Click("login.submit"),
                FlowStep.ClickIfVisible("#skip"),
                FlowStep.WaitFor(
                    listOf("session.user_name", "login.error"),
                    null,
                    2.5.seconds,
                    FlowFailure(FlowFailureReason.LOGIN_FAILED, "Login failed", "login.error"),
                ),
                FlowStep.ExpectUrl("/home$", 30.seconds, FlowFailure(null, "not home ({url})")),
                FlowStep.EmailLink(LinkPurpose.VERIFY, "verify\\?token=", open = false, into = ValueTarget.shared("verify")),
                FlowStep.EmailCode("verify.code", "verify.submit"),
                FlowStep.PhoneCode("verify.phone_code", "verify.phone_submit"),
                FlowStep.Read("#code", ValueTarget.shared("company_code"), "([A-Z0-9]{8})"),
                FlowStep.SetShared("plan", "free"),
                FlowStep.IfVisible("#consent", listOf(FlowStep.Click("#accept")), 1.seconds),
                FlowStep.SaveSession,
                FlowStep.AccountCreated,
                FlowStep.AssertIdentity("session.user_name"),
            )
    }

    @Test
    fun `steps with one natural argument accept it directly`() {
        flow(
            "login",
            """
            - click: login.submit
            - check: "#terms"
            - click_if_visible: "#later"
            - wait_for: session.user_name
            - expect_url: /home
            - email_link: invite
            - email_code: verify.code
            - phone_code: verify.phone_code
            - assert_identity: session.user_name
            - save_session: true
            - account_created:
            """,
        ).steps shouldContainExactly
            listOf(
                FlowStep.Click("login.submit"),
                FlowStep.Check("#terms"),
                FlowStep.ClickIfVisible("#later"),
                FlowStep.WaitFor(listOf("session.user_name"), null),
                FlowStep.ExpectUrl("/home"),
                FlowStep.EmailLink(LinkPurpose.INVITE),
                FlowStep.EmailCode("verify.code"),
                FlowStep.PhoneCode("verify.phone_code"),
                FlowStep.AssertIdentity("session.user_name"),
                FlowStep.SaveSession,
                FlowStep.AccountCreated,
            )
    }

    @Test
    fun `optional arguments take their defaults`() {
        val steps =
            flow(
                "login",
                """
                - email_link: {pattern: "set-password"}
                - read: {selector: "#x", into: company}
                - wait_for: {text: "Xoş gəldiniz"}
                """,
            ).steps

        steps shouldContainExactly
            listOf(
                FlowStep.EmailLink(LinkPurpose.ANY, "set-password", open = true, into = null),
                FlowStep.Read("#x", ValueTarget.vars("company"), null),
                FlowStep.WaitFor(emptyList(), "Xoş gəldiniz"),
            )
        (steps[0] as FlowStep.EmailLink).target shouldBe ValueTarget.vars("email_link")
    }

    @Test
    fun `a journey reads its pages with their reasons and stuck failures`() {
        val journey =
            flow(
                "login",
                """
                - journey:
                    label: signing in
                    until: session.user_name
                    start: the login page
                    max_visits: 3
                    pages:
                      - label: the e-mail code step
                        when: verify.code
                        steps: [{email_code: {selector: verify.code, submit: verify.submit}}]
                      - label: the login page
                        when: login.email
                        reason: login_failed
                        stuck: {reason: login_failed, message: "Login as {self.email} failed", error: login.error}
                        steps:
                          - click: login.submit
                """,
            ).steps.single()

        journey shouldBe
            FlowStep.Journey(
                label = "signing in",
                until = "session.user_name",
                start = "the login page",
                maxVisits = 3,
                pages =
                    listOf(
                        JourneyPage("the e-mail code step", "verify.code", listOf(FlowStep.EmailCode("verify.code", "verify.submit"))),
                        JourneyPage(
                            "the login page",
                            "login.email",
                            listOf(FlowStep.Click("login.submit")),
                            FlowFailureReason.LOGIN_FAILED,
                            FlowFailure(FlowFailureReason.LOGIN_FAILED, "Login as {self.email} failed", "login.error"),
                        ),
                    ),
            )
    }

    @Test
    fun `campaign flows replace the defaults of the same name and keep the others`() {
        val target = load(profile("target_profile:\n  flows:\n    login: [{click: \"#go\"}]")).target

        target.flow(FlowNames.LOGIN) shouldBe Flow(listOf(FlowStep.Click("#go")))
        target.flow(FlowNames.JOIN_BY_CODE) shouldBe TargetProfile.DEFAULT_FLOWS[FlowNames.JOIN_BY_CODE]
        target.flows.keys shouldBe FlowNames.ALL
    }

    @Test
    fun `local storage, dismiss selectors and the API prefix are read`() {
        val target =
            load(
                profile(
                    """
                    target_profile:
                      api_prefix: /api/v1
                      local_storage: {"kadro:domain_dialog_dismissed": "1", "kadro:lang": az}
                      dismiss: ["role=dialog >> text=Qəbul et", "#chat-close"]
                    """,
                ),
            ).target

        target.apiPrefix shouldBe "/api/v1"
        target.localStorage shouldBe mapOf("kadro:domain_dialog_dismissed" to "1", "kadro:lang" to "az")
        target.dismiss shouldContainExactly listOf("role=dialog >> text=Qəbul et", "#chat-close")
    }

    @Test
    fun `api placeholders become the prefix in every path sent to the target`() {
        val campaign =
            load(
                """
                campaign:
                  target: https://staging.kadrohr.test
                  testers: 3
                  seed: 7
                  roles: {admin: 1, manager: 0, employee: 2}
                  departments: [IT]
                  budget: {max_steps_per_agent: 10, max_minutes: 5}
                target_profile:
                  api_prefix: /api/v1
                  id_sources:
                    made: {oracle: {path: "{api}/latest?by={self.email}", field: id}}
                  flows:
                    login: [{goto: "{api}/health"}, {if_visible: {selector: "#x", then: [{goto: "{api}/y"}]}}]
                steps:
                  - actor: admin
                    run: {function: login, args: {where: "{api}/z"}}
                    emits: made
                    assert:
                      - http_status: {path: "{api}/leave-requests/{last_id}/approve", method: POST, equals: 403}
                      - oracle: {path: "{api}/x", field: id, equals: "1"}
                """,
            )

        campaign.steps.single().assertions shouldContainExactly
            listOf(
                AssertionSpec.HttpStatus("/api/v1/leave-requests/{last_id}/approve", "POST", 403),
                AssertionSpec.Oracle("/api/v1/x", "id", "1", null),
            )
        campaign.steps.single().action shouldBe StepAction.Run("login", mapOf("where" to "/api/v1/z"))
        campaign.target.idSources["made"] shouldBe IdSource.OracleField("/api/v1/latest?by={self.email}", "id")
        campaign.target.flows
            .getValue("login")
            .steps shouldContainExactly
            listOf(FlowStep.Goto("/api/v1/health"), FlowStep.IfVisible("#x", listOf(FlowStep.Goto("/api/v1/y"))))
    }

    @Test
    fun `without an api prefix the contract prefix is used`() {
        val campaign =
            load(
                header.replace("steps: [{actor: admin, do: \"Elan yarat\"}]", "") +
                    """
                    steps:
                      - actor: admin
                        do: "Elan yarat"
                        assert: [{http_status: {path: "{api}/tickets/1/approve", equals: 403}}]
                    """.trimIndent(),
            )

        (
            campaign.steps
                .single()
                .assertions
                .single() as AssertionSpec.HttpStatus
        ).path shouldBe "/api/tickets/1/approve"
    }

    @Test
    fun `pacing is read in milliseconds and is off by default`() {
        load(header).settings.pacing shouldBe Pacing.NONE

        val pacing = "  departments: [IT]\n  pacing: {start_stagger_ms: 1500, max_parallel_actors: 4}"
        val paced = load(header.replace("  departments: [IT]", pacing))

        paced.settings.pacing shouldBe Pacing(startStagger = 1500.milliseconds, maxParallelActors = 4)
    }

    @Test
    fun `unknown step kinds and arguments are errors on their lines`() {
        issue(profile("target_profile:\n  flows:\n    login:\n      - tap: login.submit"), "unknown flow step 'tap'").line shouldBe 12
        issue(profile("target_profile:\n  flows:\n    login:\n      - fill: {selector: a, text: b}"), "unknown key 'text'").line shouldBe 12
        issue(profile("target_profile:\n  flows:\n    login:\n      - logout"), "'logout' is not a step without arguments").line shouldBe 12
        issue(profile("target_profile:\n  flows:\n    login:\n      - {click: a, fill: b}"), "must be one step").line shouldBe 12
    }

    @Test
    fun `missing and malformed arguments are reported`() {
        val yaml =
            profile(
                """
                target_profile:
                  flows:
                    login:
                      - fill: {selector: a}
                      - wait_for: {selector: a, text: b}
                      - email_link: {purpose: magic}
                      - read: {selector: a, into: "shared.Big-Key"}
                      - wait_for: {selector: a, fail: {reason: exploded}}
                      - save_session: false
                      - wait_for: {any: []}
                      - journey: {label: x, until: y}
                      - goto:
                """,
            )

        val messages = issues(yaml).map { it.message }

        messages.any { "missing required key 'value'" in it } shouldBe true
        messages.any { "needs exactly one of selector, any or text" in it } shouldBe true
        messages.any { "must be one of verify, invite, any, was 'magic'" in it } shouldBe true
        messages.any { "must be vars.<key>, shared.<key> or <key>" in it } shouldBe true
        messages.any { "must be one of registration_failed" in it && "'exploded'" in it } shouldBe true
        messages.any { "takes no arguments" in it } shouldBe true
        messages.any { "needs at least one selector" in it } shouldBe true
        messages.any { "missing required key 'pages'" in it } shouldBe true
        messages.any { "'target_profile.flows.login[8].goto' has no value" in it } shouldBe true
    }

    @Test
    fun `flows must be lists of steps under a map of names`() {
        issue(profile("target_profile:\n  flows: [login]"), "must be a map of flow names").line shouldBe 10
        issue(profile("target_profile:\n  flows:\n    login: {click: a}"), "must be a list").line shouldBe 11
        issue(profile("target_profile:\n  flows:\n    login:"), "needs a list of steps").line shouldBe 11
    }

    @Test
    fun `a failure needs something to say`() {
        issue(profile("target_profile:\n  flows:\n    login:\n      - wait_for: {selector: a, fail: {}}"), "needs at least one of reason")
            .message shouldContain "reason, message, error"
    }

    @Test
    fun `pacing keys are strict`() {
        issue(header.replace("  departments: [IT]", "  departments: [IT]\n  pacing: {stagger: 3}"), "unknown key 'stagger'").line shouldBe 7
    }
}
