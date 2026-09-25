package az.petek.agent.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.ConsecutiveLoopDetector
import az.petek.agent.domain.FailureReason
import az.petek.agent.domain.JsonDecisionProtocol
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.FakeVerification
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.testing.FakeBrowserSession
import az.petek.core.error.PetekException
import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmRequest
import az.petek.llm.testing.ScriptedLlmClient
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.VerificationCode
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.testing.FakeTargetOracle
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAgentLoopTest {
    private val clock = FakeHarnessClock()
    private val ids = SequentialIdGenerator()
    private val evidence = InMemoryEvidence()
    private val artifacts = InMemoryArtifactStore()
    private val verification = FakeVerification()
    private val oracle = FakeTargetOracle()
    private val protocol = JsonDecisionProtocol()
    private val identity = AgentTestData.itEmployee
    private val password = identity.password.reveal()
    private val browser = loginPage(FakeBrowserSession("a04", clock))
    private val runtime = AgentTestData.runtime(browser, identity)

    /** A login page whose password field echoes what was typed, like a careless site would. */
    private fun loginPage(session: FakeBrowserSession): FakeBrowserSession {
        session.url = "https://staging.kadrohr.test/login"
        session.snapshotProvider = {
            val typed =
                session.actions
                    .lastOrNull { it.startsWith("fill 2=") }
                    ?.removePrefix("fill 2=")
                    ?.removeSuffix(" +submit")
            PageSnapshot(
                url = session.url,
                title = "KadroHR",
                elements =
                    listOf(
                        PageElement(1, "textbox", "E-poçt", "input", "login-email", null, true),
                        PageElement(2, "textbox", "Şifrə", "input", "login-password", typed, true),
                        PageElement(3, "button", "Daxil ol", "button", "login-submit", null, true),
                        PageElement(4, "combobox", "Departament", "select", "ticket-department", null, true),
                        PageElement(5, "textbox", "Kod", "input", "verify-code", null, true),
                    ),
                visibleText = "Xoş gəlmisiniz",
            )
        }
        return session
    }

    private fun decision(
        tool: String,
        args: String = "",
    ) = """{"reason": "next step", "tool": "$tool"${if (args.isEmpty()) "" else ", $args"}}"""

    private fun scripted(
        vararg answers: String,
        before: suspend (LlmRequest) -> Unit = {},
    ): ScriptedLlmClient {
        val queue = ArrayDeque(answers.toList())
        return ScriptedLlmClient { request ->
            before(request)
            val next =
                synchronized(queue) { queue.removeFirstOrNull() } ?: throw AssertionError("The LLM was asked more often than scripted")
            Json.parseToJsonElement(next).jsonObject
        }
    }

    private fun loop(
        llm: LlmClient,
        mail: AwaitVerificationUseCase = verification,
        testApi: TargetOracle = oracle,
    ) = DefaultAgentLoop(
        llm = llm,
        protocol = protocol,
        loopDetectorFactory = { ConsecutiveLoopDetector() },
        recorder = evidence,
        artifacts = artifacts,
        verification = mail,
        oracle = testApi,
        prompts = PromptBuilder(protocol),
        resolver = PlaceholderResolver(),
        clock = clock,
        ids = ids,
    )

    private suspend fun execute(
        llm: LlmClient,
        maxSteps: Int = 20,
        timeout: Duration = 10.minutes,
        target: AgentRuntime = runtime,
        mail: AwaitVerificationUseCase = verification,
        testApi: TargetOracle = oracle,
    ): ActionOutcome =
        loop(llm, mail, testApi).execute(target, "Daxil ol və elan yarat", AgentTestData.step(maxSteps = maxSteps, timeout = timeout))

    /** The fake test API with counted phone-code lookups; the first [failures] of them throw [failure]. */
    private class CountingOracle(
        private val delegate: FakeTargetOracle,
        private val failure: OracleException? = null,
        private val failures: Int = Int.MAX_VALUE,
    ) : TargetOracle by delegate {
        val otpLookups = mutableListOf<String>()

        override suspend fun latestOtp(phone: String): String? {
            otpLookups += phone
            if (failure != null && otpLookups.size <= failures) throw failure
            return delegate.latestOtp(phone)
        }
    }

    private fun ScriptedLlmClient.userTurn(index: Int): String = requests[index].messages.single().content

    private fun artifactsOf(type: ArtifactType) = evidence.artifactList.filter { it.type == type }

    @Test
    fun `a task that reaches done succeeds with the reported object id`() =
        runTest {
            val llm =
                scripted(
                    decision("navigate", """"url": "/login""""),
                    decision("type", """"ref": 1, "text": "{self.email}""""),
                    decision("type", """"ref": 2, "text": "{self.password}", "submit": true"""),
                    decision("select", """"ref": 4, "option": "IT""""),
                    decision("done", """"summary": "Elan yaradıldı", "object_id": "a42""""),
                )

            val outcome = execute(llm)

            outcome shouldBe ActionOutcome(ActionStatus.SUCCEEDED, "Elan yaradıldı", objectId = "a42", stepsTaken = 5)
            browser.actions shouldContainExactly
                listOf("navigate /login", "fill 1=${identity.email}", "fill 2=$password +submit", "select 4=IT")
            runtime.variables[AgentVariableKeys.LAST_OBJECT_ID] shouldBe "a42"
        }

    @Test
    fun `every turn is recorded as a DO step with its artifacts`() =
        runTest {
            execute(
                scripted(
                    decision("navigate", """"url": "/login""""),
                    decision("type", """"ref": 2, "text": "{self.password}", "submit": true"""),
                    decision("click", """"ref": 3"""),
                    decision("done", """"summary": "ok""""),
                ),
            )

            val steps = evidence.stepList
            steps.map { it.action } shouldContainExactly
                listOf(
                    "navigate /login",
                    "type [2] (Şifrə) \"{self.password}\" +submit",
                    "click [3] \"Daxil ol\"",
                    "done success=true \"ok\"",
                )
            steps.forEach { step ->
                step.kind shouldBe StepKind.DO
                step.status shouldBe StepStatus.PASSED
                step.llmReason shouldBe "next step"
                step.runId shouldBe AgentTestData.RUN_ID
                step.agentId shouldBe identity.agentId
                step.scenarioStep shouldBe "announce"
                step.correlationId shouldBe AgentTestData.CORRELATION_ID
            }
            steps.last().detail!! shouldContain "outcome: SUCCEEDED: ok"
            artifactsOf(ArtifactType.SCREENSHOT).map { it.stepId } shouldContainExactly steps.map { it.stepId }
            artifactsOf(ArtifactType.A11Y).map { it.stepId } shouldContainExactly listOf(steps.first().stepId, steps.last().stepId)
        }

    @Test
    fun `step timings come from the harness clock`() =
        runTest {
            execute(
                scripted(decision("click", """"ref": 3"""), decision("done", """"summary": "ok""""), before = {
                    clock.advance(1500.milliseconds)
                }),
            )

            evidence.stepList.map { it.durationMs } shouldContainExactly listOf(1500L, 1500L)
            val (first, second) = evidence.stepList
            first.endedAt shouldBe first.startedAt.plusMillis(1500)
            second.startedAt shouldBe first.endedAt
        }

    @Test
    fun `the password never reaches the model or the evidence even when the page echoes it`() =
        runTest {
            val llm =
                scripted(
                    decision("type", """"ref": 2, "text": "{self.password}""""),
                    decision("read_text", """"selector": "#echo""""),
                    decision("type", """"ref": 1, "text": "{self.passwordx}""""),
                    decision("done", """"summary": "ok""""),
                )
            browser.selectorTexts["#echo"] = "Your password is $password"

            execute(llm)

            browser.actions.first() shouldBe "fill 2=$password"
            llm.requests shouldHaveSize 4
            llm.requests.forEach { request ->
                request.system shouldNotContain password
                request.messages.forEach { it.content shouldNotContain password }
            }
            llm.userTurn(1) shouldContain "value=\"{self.password}\""
            llm.userTurn(2) shouldContain "Your password is {self.password}"
            evidence.stepList.forEach { step ->
                listOfNotNull(step.action, step.detail, step.llmReason).forEach { it shouldNotContain password }
            }
        }

    @Test
    fun `an invalid decision is fed back and the model can recover`() =
        runTest {
            val llm = scripted(decision("click"), decision("click", """"ref": 3"""), decision("done", """"summary": "ok""""))

            val outcome = execute(llm)

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.stepsTaken shouldBe 3
            llm.userTurn(1) shouldContain "invalid decision -> INVALID: Tool 'click' needs 'ref'"
            evidence.stepList.first().action shouldBe "invalid decision"
            evidence.stepList.first().status shouldBe StepStatus.FAILED
            browser.actions shouldContainExactly listOf("click 3")
        }

    @Test
    fun `two invalid decisions in a row fail the step`() =
        runTest {
            val llm = scripted(decision("execute_js", """"text": "x""""), decision("hover", """"ref": 1"""))

            val outcome = execute(llm)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.INVALID_DECISION
            outcome.stepsTaken shouldBe 2
            outcome.summary shouldContain "Unknown tool 'hover'"
            llm.requests shouldHaveSize 2
            browser.actions shouldHaveSize 0
        }

    @Test
    fun `an element that is not on the page is rejected before the browser is touched`() =
        runTest {
            val llm = scripted(decision("click", """"ref": 99"""), decision("done", """"summary": "ok""""))

            execute(llm)

            browser.actions shouldHaveSize 0
            llm.userTurn(1) shouldContain "Element [99] is not on the current page"
        }

    @Test
    fun `the agent cannot navigate to another host, only within the site under test`() =
        runTest {
            val llm =
                scripted(
                    decision("navigate", """"url": "https://kadrohr.com/register""""),
                    decision("navigate", """"url": "https://STAGING.kadrohr.test/tickets""""),
                    decision("navigate", """"url": "/announcements""""),
                    decision("done", """"summary": "ok""""),
                )

            execute(llm).status shouldBe ActionStatus.SUCCEEDED

            browser.actions shouldContainExactly listOf("navigate https://STAGING.kadrohr.test/tickets", "navigate /announcements")
            llm.userTurn(1) shouldContain
                "INVALID: Only pages of the site under test can be opened. Use a path such as /tickets or an absolute URL on staging.kadrohr.test."
            evidence.stepList.first().status shouldBe StepStatus.FAILED
        }

    @Test
    fun `on a blank page only paths can be opened`() =
        runTest {
            browser.url = "about:blank"
            val llm =
                scripted(
                    decision("navigate", """"url": "https://staging.kadrohr.test/login""""),
                    decision("navigate", """"url": "/login""""),
                    decision("done", """"summary": "ok""""),
                )

            execute(llm)

            browser.actions shouldContainExactly listOf("navigate /login")
            llm.userTurn(1) shouldContain "INVALID: Only pages of the site under test can be opened. Use a path such as /tickets."
        }

    @Test
    fun `an unknown placeholder is reported to the model instead of being typed`() =
        runTest {
            val llm = scripted(decision("type", """"ref": 1, "text": "{self.salary}""""), decision("done", """"summary": "ok""""))

            val outcome = execute(llm)

            outcome.status shouldBe ActionStatus.SUCCEEDED
            browser.actions shouldHaveSize 0
            llm.userTurn(1) shouldContain "INVALID: Unknown placeholder {self.salary}."
        }

    @Test
    fun `repeating the same action is stopped as a loop and the repeat is not executed`() =
        runTest {
            val click = decision("click", """"ref": 3""")

            val outcome = execute(scripted(click, click, click))

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.LOOP_DETECTED
            outcome.stepsTaken shouldBe 3
            browser.actions shouldContainExactly listOf("click 3", "click 3")
            evidence.stepList.last().status shouldBe StepStatus.FAILED
            artifactsOf(ArtifactType.A11Y).last().stepId shouldBe evidence.stepList.last().stepId
        }

    @Test
    fun `waiting for the same text again and again is a loop too`() =
        runTest {
            val wait = decision("wait_text", """"text": "Sabah 10:00", "timeout_s": 5""")

            val outcome = execute(scripted(wait, wait, wait))

            outcome.failureReason shouldBe FailureReason.LOOP_DETECTED
        }

    @Test
    fun `the step limit ends a task that never finishes`() =
        runTest {
            val llm = scripted(decision("click", """"ref": 1"""), decision("click", """"ref": 3"""), decision("click", """"ref": 1"""))

            val outcome = execute(llm, maxSteps = 3)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.STEP_LIMIT
            outcome.stepsTaken shouldBe 3
            llm.requests shouldHaveSize 3
            llm.userTurn(2) shouldContain "Decision 3 of at most 3."
            artifactsOf(ArtifactType.A11Y).last().stepId shouldBe evidence.stepList.last().stepId
        }

    @Test
    fun `a budget of zero decisions fails without asking the model`() =
        runTest {
            val llm = scripted()

            val outcome = execute(llm, maxSteps = 0)

            outcome.failureReason shouldBe FailureReason.STEP_LIMIT
            llm.requests shouldHaveSize 0
            evidence.stepList.single().action shouldBe "step limit"
        }

    @Test
    fun `a permission refusal is the expected blocked outcome`() =
        runTest {
            val outcome =
                execute(scripted(decision("report_problem", """"kind": "permission_denied", "note": "Approve düyməsi yoxdur"""")))

            outcome shouldBe
                ActionOutcome(
                    ActionStatus.BLOCKED,
                    "permission_denied: Approve düyməsi yoxdur",
                    failureReason = FailureReason.PERMISSION_DENIED,
                    stepsTaken = 1,
                )
            evidence.stepList.single().status shouldBe StepStatus.BLOCKED
        }

    @Test
    fun `any other reported problem fails the step`() =
        runTest {
            val outcome = execute(scripted(decision("report_problem", """"kind": "bug", "note": "500 xətası"""")))

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.PROBLEM_REPORTED
            outcome.summary shouldBe "bug: 500 xətası"
        }

    @Test
    fun `done with success false fails with the model's summary`() =
        runTest {
            val outcome = execute(scripted(decision("done", """"summary": "Ticket tapılmadı", "success": false""")))

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.PROBLEM_REPORTED
            outcome.summary shouldBe "Ticket tapılmadı"
            runtime.variables[AgentVariableKeys.LAST_OBJECT_ID].shouldBeNull()
        }

    @Test
    fun `three failed browser actions in a row fail the step`() =
        runTest {
            browser.failOn = { it.startsWith("click") }
            val llm = scripted(decision("click", """"ref": 1"""), decision("click", """"ref": 3"""), decision("click", """"ref": 4"""))

            val outcome = execute(llm)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.BROWSER_ERROR
            outcome.summary shouldContain "3 failed actions in a row"
            llm.userTurn(1) shouldContain "ERROR: scripted failure: click 1"
            evidence.stepList.map { it.status } shouldContainExactly List(3) { StepStatus.ERROR }
            artifactsOf(ArtifactType.A11Y) shouldHaveSize 3
        }

    @Test
    fun `a successful action resets the browser error streak`() =
        runTest {
            browser.failOn = { it.startsWith("click") }
            val llm =
                scripted(
                    decision("click", """"ref": 1"""),
                    decision("click", """"ref": 3"""),
                    decision("navigate", """"url": "/""""),
                    decision("click", """"ref": 4"""),
                    decision("click", """"ref": 1"""),
                    decision("done", """"summary": "ok""""),
                )

            execute(llm).status shouldBe ActionStatus.SUCCEEDED
        }

    @Test
    fun `a page that cannot be read counts as a failed action`() =
        runTest {
            browser.snapshotProvider = { throw BrowserActionException("page crashed") }
            val llm = scripted()

            val outcome = execute(llm)

            outcome.failureReason shouldBe FailureReason.BROWSER_ERROR
            outcome.stepsTaken shouldBe 0
            llm.requests shouldHaveSize 0
            evidence.stepList.map { it.action } shouldContainExactly List(3) { "snapshot" }
        }

    @Test
    fun `read_text and wait_text report what they observed without failing the task`() =
        runTest {
            browser.selectorTexts["#status"] = "  In progress\n"
            browser.visibleTexts += "Hazır"
            val llm =
                scripted(
                    decision("read_text", """"selector": "#status""""),
                    decision("read_text", """"selector": "#missing""""),
                    decision("wait_text", """"text": "Hazır""""),
                    decision("wait_text", """"text": "Yox", "timeout_s": 3"""),
                    decision("done", """"summary": "ok""""),
                )

            execute(llm).status shouldBe ActionStatus.SUCCEEDED

            val lastTurn = llm.userTurn(4)
            lastTurn shouldContain "OK: text is \"In progress\"."
            lastTurn shouldContain "No element matches #missing."
            lastTurn shouldContain "OK: \"Hazır\" is visible."
            lastTurn shouldContain "\"Yox\" did not appear within 3s."
            evidence.stepList.map { it.status }.take(4) shouldContainExactly
                listOf(StepStatus.PASSED, StepStatus.FAILED, StepStatus.PASSED, StepStatus.FAILED)
        }

    @Test
    fun `get_email_code stores the code and the model types it through a placeholder`() =
        runTest {
            verification.sendCode(identity.email, "654321")
            val llm =
                scripted(
                    decision("get_email_code"),
                    decision("type", """"ref": 5, "text": "{vars.email_code}", "submit": true"""),
                    decision("done", """"summary": "verified""""),
                )

            execute(llm).status shouldBe ActionStatus.SUCCEEDED

            runtime.variables[AgentVariableKeys.EMAIL_CODE] shouldBe "654321"
            browser.actions shouldContainExactly listOf("fill 5=654321 +submit")
            llm.userTurn(1) shouldContain "verification code stored; type {vars.email_code} to enter it."
            llm.userTurn(1) shouldContain "{vars.email_code}"
            llm.requests.forEach { request -> request.messages.single().content shouldNotContain "654321" }
            verification.calls.single().first shouldBe identity.email
        }

    @Test
    fun `an e-mail without a code or an unexpected mail error is a failed action the model can react to`() =
        runTest {
            var calls = 0
            val odd =
                object : AwaitVerificationUseCase {
                    override suspend fun await(
                        to: String,
                        since: Instant,
                        purpose: MailPurpose,
                        timeout: Duration,
                        pollInterval: Duration,
                    ): VerificationCode {
                        calls++
                        if (calls == 1) return VerificationCode(null, URI("https://x/confirm"), "m1")
                        throw PetekException("Mailpit is unreachable")
                    }
                }
            val llm = scripted(decision("get_email_code"), decision("get_email_code"), decision("done", """"summary": "ok""""))

            val outcome = execute(llm, mail = odd)

            outcome.status shouldBe ActionStatus.SUCCEEDED
            llm.userTurn(2) shouldContain "ERROR: the newest verification e-mail contains no code."
            llm.userTurn(2) shouldContain "ERROR: Mailpit is unreachable"
            evidence.stepList.take(2).map { it.status } shouldContainExactly listOf(StepStatus.ERROR, StepStatus.ERROR)
        }

    @Test
    fun `a missing verification e-mail fails the step with mail_timeout`() =
        runTest {
            val outcome = execute(scripted(decision("get_email_code")))

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.MAIL_TIMEOUT
            currentTime shouldBe 60_000
        }

    @Test
    fun `an unreachable test inbox ends the step with mail_unavailable, an environment error`() =
        runTest {
            val unreachable =
                object : AwaitVerificationUseCase {
                    override suspend fun await(
                        to: String,
                        since: Instant,
                        purpose: MailPurpose,
                        timeout: Duration,
                        pollInterval: Duration,
                    ): VerificationCode {
                        delay(timeout)
                        throw MailboxException("Mailpit at http://127.0.0.1:8025: search failed (ConnectException: Connection refused)")
                    }
                }
            val llm = scripted(decision("get_email_code"))

            val outcome = execute(llm, mail = unreachable)

            outcome.status shouldBe ActionStatus.ERROR
            outcome.failureReason shouldBe FailureReason.MAIL_UNAVAILABLE
            outcome.summary shouldBe
                "Test inbox unreachable: Mailpit at http://127.0.0.1:8025: search failed (ConnectException: Connection refused)"
            outcome.stepsTaken shouldBe 1
            llm.requests shouldHaveSize 1
            val step = evidence.stepList.single()
            step.action shouldBe "get_email_code"
            step.status shouldBe StepStatus.ERROR
            step.detail!! shouldContain "outcome: ERROR mail_unavailable: Test inbox unreachable"
            artifactsOf(ArtifactType.SCREENSHOT) shouldHaveSize 1
            artifactsOf(ArtifactType.A11Y) shouldHaveSize 1
            runtime.variables[AgentVariableKeys.EMAIL_CODE].shouldBeNull()
        }

    @Test
    fun `get_phone_code stores the phone code and the model types it through a placeholder`() =
        runTest {
            oracle.otps[identity.phone] = "482913"
            val llm =
                scripted(
                    decision("get_phone_code"),
                    decision("type", """"ref": 5, "text": "{vars.phone_code}", "submit": true"""),
                    decision("done", """"summary": "phone verified""""),
                )

            execute(llm).status shouldBe ActionStatus.SUCCEEDED

            runtime.variables[AgentVariableKeys.PHONE_CODE] shouldBe "482913"
            browser.actions shouldContainExactly listOf("fill 5=482913 +submit")
            llm.userTurn(1) shouldContain "1. get_phone_code -> OK: phone code stored; type {vars.phone_code} to enter it."
            llm.userTurn(1) shouldContain "{vars.phone_code}"
            llm.requests.forEach { request -> request.messages.single().content shouldNotContain "482913" }
            evidence.stepList.first().action shouldBe "get_phone_code"
            evidence.stepList.first().status shouldBe StepStatus.PASSED
            currentTime shouldBe 0
        }

    @Test
    fun `get_phone_code waits a few seconds for a code the site has not published yet`() =
        runTest {
            val counting = CountingOracle(oracle)
            launch {
                delay(1500.milliseconds)
                oracle.otps[identity.phone] = " 604418 "
            }
            val llm = scripted(decision("get_phone_code"), decision("done", """"summary": "ok""""))

            execute(llm, testApi = counting).status shouldBe ActionStatus.SUCCEEDED

            runtime.variables[AgentVariableKeys.PHONE_CODE] shouldBe "604418"
            counting.otpLookups shouldBe List(3) { identity.phone }
            currentTime shouldBe 2_000
        }

    @Test
    fun `without a phone code after a few seconds the model is told so and can react`() =
        runTest {
            val counting = CountingOracle(oracle)
            val llm =
                scripted(
                    decision("get_phone_code"),
                    decision("report_problem", """"kind": "blocked", "note": "Telefon kodu gəlmədi""""),
                )

            val outcome = execute(llm, testApi = counting)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.PROBLEM_REPORTED
            llm.userTurn(1) shouldContain
                "get_phone_code -> No phone code has been sent to your phone yet (the test API was asked 5 times)."
            counting.otpLookups shouldHaveSize DefaultAgentLoop.PHONE_CODE_ATTEMPTS
            currentTime shouldBe 4_000
            evidence.stepList.first().status shouldBe StepStatus.FAILED
            runtime.variables[AgentVariableKeys.PHONE_CODE].shouldBeNull()
        }

    @Test
    fun `a run without the test API tells the model the phone code is unavailable`() =
        runTest {
            val noTestApi = CountingOracle(FakeTargetOracle(isAvailable = false))
            val llm = scripted(decision("get_phone_code"), decision("done", """"summary": "stuck", "success": false"""))

            execute(llm, testApi = noTestApi)

            llm.userTurn(1) shouldContain
                "get_phone_code -> Phone code unavailable: this run has no access to the site's test API, the only source of phone codes."
            noTestApi.otpLookups shouldHaveSize 0
            evidence.stepList.first().status shouldBe StepStatus.FAILED
            currentTime shouldBe 0
        }

    @Test
    fun `a failing test API makes the phone code unavailable instead of crashing the step`() =
        runTest {
            val failing = CountingOracle(oracle, OracleException("GET /test/otp/%2B994501000004 answered HTTP 500"))
            oracle.otps[identity.phone] = "111111"
            val llm = scripted(decision("get_phone_code"), decision("done", """"summary": "stuck", "success": false"""))

            execute(llm, testApi = failing)

            llm.userTurn(1) shouldContain "get_phone_code -> ERROR: phone code unavailable: GET /test/otp/%2B994501000004 answered HTTP 500"
            failing.otpLookups shouldHaveSize DefaultAgentLoop.PHONE_CODE_ATTEMPTS
            evidence.stepList.first().status shouldBe StepStatus.ERROR
            runtime.variables[AgentVariableKeys.PHONE_CODE].shouldBeNull()
        }

    @Test
    fun `a test API that recovers within the retries still delivers the phone code`() =
        runTest {
            val flaky = CountingOracle(oracle, OracleException("GET /test/otp timed out"), failures = 1)
            oracle.otps[identity.phone] = "777000"
            val llm = scripted(decision("get_phone_code"), decision("done", """"summary": "ok""""))

            execute(llm, testApi = flaky).status shouldBe ActionStatus.SUCCEEDED

            runtime.variables[AgentVariableKeys.PHONE_CODE] shouldBe "777000"
            llm.userTurn(1) shouldContain "OK: phone code stored"
            flaky.otpLookups shouldHaveSize 2
            currentTime shouldBe 1_000
        }

    @Test
    fun `an unavailable LLM ends the step at once with an error`() =
        runTest {
            val llm = ScriptedLlmClient { throw LlmException.Unavailable("claude CLI is not logged in") }

            val outcome = execute(llm)

            outcome.status shouldBe ActionStatus.ERROR
            outcome.failureReason shouldBe FailureReason.LLM_UNAVAILABLE
            outcome.summary shouldContain "claude CLI is not logged in"
            outcome.stepsTaken shouldBe 0
            llm.requests shouldHaveSize 1
            evidence.stepList.single().status shouldBe StepStatus.ERROR
            artifactsOf(ArtifactType.SCREENSHOT) shouldHaveSize 1
            artifactsOf(ArtifactType.A11Y) shouldHaveSize 1
        }

    @Test
    fun `retry-exhausted transient LLM failures are not retried again`() =
        runTest {
            val llm = ScriptedLlmClient { throw LlmException.Transient("overloaded") }

            val outcome = execute(llm)

            outcome.failureReason shouldBe FailureReason.LLM_UNAVAILABLE
            llm.requests shouldHaveSize 1
        }

    @Test
    fun `an answer that does not match the schema counts as an invalid decision`() =
        runTest {
            var calls = 0
            val llm =
                ScriptedLlmClient {
                    calls++
                    if (calls == 1) throw LlmException.InvalidOutput("not JSON", raw = "hmm")
                    Json.parseToJsonElement(decision("done", """"summary": "ok"""")).jsonObject
                }

            val outcome = execute(llm)

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.stepsTaken shouldBe 2
            llm.userTurn(1) shouldContain "INVALID: Your answer was not a JSON object matching the schema"
        }

    @Test
    fun `the whole task is bounded by the step timeout`() =
        runTest {
            var ref = 0
            val llm =
                ScriptedLlmClient {
                    delay(40.seconds)
                    ref = if (ref == 1) 3 else 1
                    Json.parseToJsonElement(decision("click", """"ref": $ref""")).jsonObject
                }

            val outcome = execute(llm, timeout = 90.seconds)

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.TIMEOUT
            outcome.stepsTaken shouldBe 2
            currentTime shouldBe 90_000
            evidence.stepList.last().action shouldBe "timeout"
            artifactsOf(ArtifactType.A11Y).last().stepId shouldBe evidence.stepList.last().stepId
        }

    @Test
    fun `a task finished just before the timeout keeps its outcome while its evidence is still being captured`() =
        runTest {
            val slowCamera =
                object : BrowserSession by browser {
                    override suspend fun screenshot(): ByteArray {
                        delay(5.seconds)
                        return browser.screenshot()
                    }
                }
            val llm =
                scripted(decision("done", """"summary": "Elan yaradıldı", "object_id": "a7""""), before = {
                    delay(58.seconds)
                })

            val outcome = execute(llm, timeout = 60.seconds, target = AgentTestData.runtime(slowCamera, identity))

            outcome shouldBe ActionOutcome(ActionStatus.SUCCEEDED, "Elan yaradıldı", objectId = "a7", stepsTaken = 1)
            currentTime shouldBe 60_000
            evidence.stepList.map { it.action } shouldContainExactly listOf("done success=true \"Elan yaradıldı\" object_id=a7")
        }

    @Test
    fun `a password that a GET form put into the page URL is redacted too`() =
        runTest {
            val encoded = URLEncoder.encode(password, StandardCharsets.UTF_8)
            encoded shouldNotBe password
            browser.url = "https://staging.kadrohr.test/login?email=x&password=$encoded"
            val llm = scripted(decision("read_text", """"selector": "#echo""""), decision("done", """"summary": "ok""""))
            browser.selectorTexts["#echo"] = "query: password=$encoded"

            execute(llm)

            llm.requests.forEach { request -> request.messages.single().content shouldNotContain encoded }
            llm.userTurn(0) shouldContain "password={self.password}"
            llm.userTurn(1) shouldContain "query: password={self.password}"
            evidence.stepList.forEach { step -> listOfNotNull(step.action, step.detail).forEach { it shouldNotContain encoded } }
        }

    @Test
    fun `every request carries the same cacheable system prompt, the schema and an agent label`() =
        runTest {
            val llm =
                scripted(decision("click", """"ref": 3"""), decision("click", """"ref": 1"""), decision("done", """"summary": "ok""""))

            execute(llm)

            llm.requests.map { it.system }.distinct() shouldHaveSize 1
            llm.requests.map { it.label }.distinct() shouldContainExactly listOf("a04/announce")
            llm.requests.forEach { it.responseSchema shouldBe protocol.responseSchema() }
            llm.userTurn(2) shouldContain "1. click [3] \"Daxil ol\" -> OK: clicked."
            llm.userTurn(2) shouldContain "2. click [1] \"E-poçt\" -> OK: clicked."
            llm.userTurn(0) shouldContain "Task: Daxil ol və elan yarat"
        }

    @Test
    fun `one loop serves concurrent agents without mixing their state`() =
        runTest {
            val otherBrowser = loginPage(FakeBrowserSession("a05", clock))
            val other = AgentTestData.runtime(otherBrowser, AgentTestData.salesEmployee)
            val scripts =
                mapOf(
                    "a04/announce" to ArrayDeque(listOf(decision("click", """"ref": 1"""), decision("done", """"summary": "a04 done""""))),
                    "a05/announce" to ArrayDeque(listOf(decision("click", """"ref": 3"""), decision("done", """"summary": "a05 done""""))),
                )
            val llm =
                ScriptedLlmClient { request ->
                    delay(10.milliseconds)
                    val next = synchronized(scripts) { scripts.getValue(request.label).removeFirst() }
                    Json.parseToJsonElement(next).jsonObject
                }
            val shared = loop(llm)
            val step = AgentTestData.step()

            val first = async { shared.execute(runtime, "task", step) }
            val second = async { shared.execute(other, "task", step) }

            first.await().summary shouldBe "a04 done"
            second.await().summary shouldBe "a05 done"
            browser.actions shouldContainExactly listOf("click 1")
            otherBrowser.actions shouldContainExactly listOf("click 3")
            evidence.stepList.groupBy { it.agentId }.mapValues { it.value.size } shouldBe
                mapOf(identity.agentId to 2, AgentTestData.salesEmployee.agentId to 2)
        }

    @Test
    fun `an invalid decision without a reason is recorded without one`() =
        runTest {
            val invalid: JsonObject = Json.parseToJsonElement("""{"tool": "click", "ref": 1}""").jsonObject
            var calls = 0
            val llm =
                ScriptedLlmClient {
                    calls++
                    if (calls == 1) invalid else Json.parseToJsonElement(decision("done", """"summary": "ok"""")).jsonObject
                }

            execute(llm).status shouldBe ActionStatus.SUCCEEDED
            evidence.stepList
                .first()
                .llmReason
                .shouldBeNull()
        }
}
