package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.StepAction
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RunnerEventsTest {
    private fun TestScope.fixture(oracle: FakeTargetOracle = FakeTargetOracle()) = RunnerFixture(VirtualClock(testScheduler), oracle)

    private fun emitting(
        idSource: IdSource?,
        campaignSources: Map<String, IdSource> = emptyMap(),
    ) = campaign(
        steps = listOf(step("create", admin(), emits = "thing_created", idSource = idSource)),
        idSources = campaignSources,
    )

    private fun RunnerFixture.event(): EventRecord = evidence.eventList.single()

    @Test
    fun `an id from the page url is the first capture group of the regex`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, runtime ->
                f.browser.session(runtime.identity.agentId.value).url = "https://staging.example.test/tickets/42?tab=history"
                ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "llm-says-7")
            }

            val summary = f.runner().run(emitting(IdSource.UrlRegex("/tickets/(\\d+)")))

            f.event().objectId shouldBe "42"
            f.event().objectIdSource shouldBe "url_regex"
            f.buses
                .single()
                .latest("thing_created")
                ?.objectId shouldBe "42"
            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `an id from the oracle uses the rendered path and the selected field`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, runtime ->
                f.oracle.respond("/test/announcements/latest?by=${runtime.identity.email}", """{"id": "a9", "status": "published"}""")
                ActionOutcome(ActionStatus.SUCCEEDED, "created")
            }

            f.runner().run(emitting(IdSource.OracleField("/test/announcements/latest?by={self.email}", "id")))

            f.event().objectId shouldBe "a9"
            f.event().objectIdSource shouldBe "oracle"
        }

    @Test
    fun `an id from a dom attribute is read from the emitter's page`() =
        runTest {
            val f = fixture()
            f.browser.configure = { it.attributes["[data-testid=announcement-item]" to "data-id"] = "n5" }

            f.runner().run(emitting(IdSource.DomAttribute("[data-testid=announcement-item]", "data-id")))

            f.event().objectId shouldBe "n5"
            f.event().objectIdSource shouldBe "dom"
        }

    @Test
    fun `without an id source the agent's reported id is used and labelled as such`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "r1") }

            f.runner().run(emitting(null))
            f.event().objectId shouldBe "r1"
            f.event().objectIdSource shouldBe "agent_report"
        }

    @Test
    fun `an explicit agent report source behaves like no source`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "r2") }

            f.runner().run(emitting(IdSource.AgentReport))

            f.event().objectIdSource shouldBe "agent_report"
            f.event().objectId shouldBe "r2"
        }

    @Test
    fun `the target profile's id source applies when the step has none`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, runtime ->
                f.browser.session(runtime.identity.agentId.value).url = "https://staging.example.test/announcements/77"
                ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "r3")
            }

            f.runner().run(emitting(null, mapOf("thing_created" to IdSource.UrlRegex("/announcements/(\\d+)"))))

            f.event().objectId shouldBe "77"
        }

    @Test
    fun `a configured source that finds nothing fails the emit and falls back to the agent's id`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "r4") }

            val summary = f.runner().run(emitting(IdSource.UrlRegex("/tickets/(\\d+)")))

            f.event().objectId shouldBe "r4"
            f.event().objectIdSource shouldBe "agent_report"
            val emit = f.step("create", StepKind.EMIT, "a01")
            emit.status shouldBe StepStatus.FAILED
            emit.detail!! shouldContain "id_unavailable: url_regex:"
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `an oracle answer without the id fails the emit`() =
        runTest {
            val f = fixture()
            f.oracle.respond("/test/tickets/latest", """{"status": "open"}""")

            f.runner().run(emitting(IdSource.OracleField("/test/tickets/latest", "id")))

            f.event().objectId.shouldBeNull()
            f.event().objectIdSource.shouldBeNull()
            f.step("create", StepKind.EMIT, "a01").detail!! shouldContain "field 'id' missing"
        }

    @Test
    fun `without a test API the oracle source falls back to the agent without failing`() =
        runTest {
            val f = fixture(FakeTargetOracle(isAvailable = false))
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "r5") }

            val summary = f.runner().run(emitting(IdSource.OracleField("/test/tickets/latest", "id")))

            f.event().objectId shouldBe "r5"
            f.event().objectIdSource shouldBe "agent_report"
            f.step("create", StepKind.EMIT, "a01").detail!! shouldContain "test API not available"
            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `an agent-reported id that could climb out of a request path is never published`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "../../companies/c1") }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("create", admin(), emits = "thing_created"),
                            step(
                                "approve",
                                employees("IT", nth = 1),
                                assertions = listOf(AssertionSpec.HttpStatus("/api/tickets/{last_id}/approve", "POST", 403)),
                            ),
                        ),
                )

            val summary = f.runner().run(campaign)

            f.event().objectId.shouldBeNull()
            f.event().objectIdSource.shouldBeNull()
            val emit = f.step("create", StepKind.EMIT, "a01")
            emit.status shouldBe StepStatus.FAILED
            emit.detail!! shouldContain "id_unavailable: agent_report: rejected unsafe id '../../companies/c1'"
            f.verify.actorCalls
                .single()
                .second.templates.lastId
                .shouldBeNull()
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `an unsafe id from a configured source falls back to the agent's id and fails the emit`() =
        runTest {
            val f = fixture()
            f.browser.configure = { it.attributes["[data-testid=ticket-item]" to "data-id"] = "7/approve?as=admin" }
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "t7") }

            f.runner().run(emitting(IdSource.DomAttribute("[data-testid=ticket-item]", "data-id")))

            f.event().objectId shouldBe "t7"
            f.event().objectIdSource shouldBe "agent_report"
            val emit = f.step("create", StepKind.EMIT, "a01")
            emit.status shouldBe StepStatus.FAILED
            emit.detail!! shouldContain "dom: rejected unsafe id '7/approve?as=admin'"
        }

    @Test
    fun `ids are trimmed before they are checked and published`() =
        runTest {
            val f = fixture()
            f.browser.configure = { it.attributes["[data-testid=ticket-item]" to "data-id"] = "  t8\n" }

            f.runner().run(emitting(IdSource.DomAttribute("[data-testid=ticket-item]", "data-id")))

            f.event().objectId shouldBe "t8"
            f.event().objectIdSource shouldBe "dom"
        }

    @Test
    fun `usual id shapes are safe and path-changing ones are not`() {
        listOf("42", "a9", "t_1", "0199a1b2-7c3d-7e4f-8a9b-0c1d2e3f4a5b", "PTK-4821", "c1:2", "v1.2").forEach {
            ObjectIdReader.isSafeId(it) shouldBe true
        }
        listOf("", "..", "a/..", "1/approve", "1?x=2", "1#frag", "a b", "%2e%2e", "x".repeat(257), "a\\b").forEach {
            ObjectIdReader.isSafeId(it) shouldBe false
        }
    }

    @Test
    fun `nothing is emitted when the action did not succeed`() =
        runTest {
            val f = fixture()
            f.agents.script = { _, _ -> ActionOutcome(ActionStatus.FAILED, "button missing", failureReason = FailureReason.STEP_LIMIT) }

            f.runner().run(emitting(null))

            f.evidence.eventList.shouldBeEmpty()
            f.steps("create", StepKind.EMIT).shouldBeEmpty()
        }

    @Test
    fun `a waiter that times out fails its wait and skips its action and assertions`() =
        runTest {
            val f = fixture()
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step(
                                "read",
                                employees(),
                                waitFor = "never_sent",
                                waitTimeout = 20.seconds,
                                assertions =
                                    listOf(
                                        AssertionSpec.VisibleText("x", 5.seconds),
                                        AssertionSpec.Oracle("/test/x", null, null, null),
                                    ),
                            ),
                        ),
                )

            val summary = f.runner().run(campaign)

            f.agents.calls.shouldBeEmpty()
            val wait = f.step("read", StepKind.WAIT, "a04")
            wait.status shouldBe StepStatus.FAILED
            wait.detail shouldBe "not_received: never_sent was not published within 20s"
            wait.durationMs shouldBe 20_000
            f.step("read", StepKind.DO, "a04").status shouldBe StepStatus.SKIPPED
            f.evidence.assertionList
                .filter { it.agentId == AgentId("a04") }
                .map { it.verdict } shouldBe
                listOf(Verdict.SKIPPED, Verdict.SKIPPED)
            f.evidence.receiptList.shouldBeEmpty()
            f.evidence.artifactList
                .map { it.stepId }
                .toSet() shouldBe f.steps("read", StepKind.WAIT).map { it.stepId }.toSet()
            summary.outcome shouldBe RunOutcome.FAILED
            summary.stepsFailed shouldBe 4
            currentTime shouldBe 20_000
        }

    @Test
    fun `an event that arrives after a waiter's deadline is recorded as not received for that waiter`() =
        runTest {
            val f = fixture()
            f.busFactory = {
                val delegate = InProcessEventBus(f.clock, f.ids)
                object : EventBus by delegate {
                    override suspend fun await(
                        name: String,
                        afterSequence: Long,
                        timeout: Duration,
                    ): PublishedEvent? {
                        val seen = delegate.await(name, afterSequence, timeout)
                        if (seen == null && delegate.latest(name) == null) delegate.publish(name, "late-1", AgentId("a01"))
                        return seen
                    }
                }
            }
            val campaign =
                campaign(steps = listOf(step("read", employees("IT", nth = 1), waitFor = "announcement_created", waitTimeout = 5.seconds)))

            f.runner().run(campaign)

            val receipt = f.evidence.receiptList.single()
            receipt.receiver shouldBe AgentId("a04")
            receipt.received shouldBe false
            receipt.t1.shouldBeNull()
            receipt.latencyMs.shouldBeNull()
            receipt.eventId shouldBe
                f.buses
                    .single()
                    .latest("announcement_created")
                    ?.eventId
        }

    @Test
    fun `reception is checked right after the event arrives, before the receiver acts`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, runtime ->
                when (call.scenarioStep) {
                    "announce" -> {
                        ActionOutcome(ActionStatus.SUCCEEDED, "created", objectId = "a1")
                    }

                    "pause" -> {
                        delay(2.seconds)
                        ActionOutcome(ActionStatus.SUCCEEDED, "waited")
                    }

                    else -> {
                        // a06 only sees the text after its own action: that is not live delivery.
                        f.browser.session(runtime.identity.agentId.value).visibleTexts += "Elan"
                        ActionOutcome(ActionStatus.SUCCEEDED, "read")
                    }
                }
            }
            f.browser.configure = { if (it.label != "a06") it.visibleTexts += "Elan" }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("announce", admin(), emits = "announcement_created"),
                            step("pause", admin()),
                            step(
                                "read",
                                employees(),
                                waitFor = "announcement_created",
                                assertions = listOf(AssertionSpec.VisibleText("Elan", 5.seconds), AssertionSpec.LatencyMax(1.seconds)),
                            ),
                        ),
                )

            val summary = f.runner().run(campaign)

            val receipts = f.evidence.receiptList.associateBy { it.receiver.value }
            receipts.getValue("a04").received shouldBe true
            receipts.getValue("a04").latencyMs shouldBe 2_000
            receipts.getValue("a06").received shouldBe false
            val visible =
                f.evidence.assertionList
                    .filter { it.type == "visible_text" }
                    .associate { it.agentId?.value to it.verdict }
            visible shouldBe mapOf("a04" to Verdict.PASSED, "a05" to Verdict.PASSED, "a06" to Verdict.FAILED, "a07" to Verdict.PASSED)
            f.evidence.assertionList
                .filter { it.type == "latency_max" }
                .map { it.verdict }
                .toSet() shouldBe setOf(Verdict.FAILED)
            val waitStep = f.step("read", StepKind.WAIT, "a04")
            f.evidence.assertionList
                .filter { it.agentId == AgentId("a04") }
                .map { it.stepId }
                .toSet() shouldContainExactly setOf(waitStep.stepId)
            summary.assertionsFailed shouldBe 5
        }

    @Test
    fun `without visible_text a waiter counts as received when the event arrives`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "announce") delay(3.seconds)
                ActionOutcome(ActionStatus.SUCCEEDED, "ok", objectId = "a1")
            }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("announce", admin(), emits = "announcement_created"),
                            step("read", employees("HR"), waitFor = "announcement_created"),
                        ),
                )

            f.runner().run(campaign)

            val receipts = f.evidence.receiptList
            receipts.map { it.receiver.value } shouldContainExactly listOf("a05", "a07")
            receipts.all {
                it.received && it.latencyMs == null && it.t1 ==
                    f.evidence.eventList
                        .single()
                        .t0
            } shouldBe true
        }

    @Test
    fun `last_id prefers the actor's own emitted id over the awaited one`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                ActionOutcome(
                    ActionStatus.SUCCEEDED,
                    "ok",
                    objectId =
                        if (call.scenarioStep ==
                            "ticket"
                        ) {
                            "t1"
                        } else {
                            "c${call.agentId.index}"
                        },
                )
            }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                            step(
                                "comment",
                                employees("HR"),
                                waitFor = "ticket_created",
                                emits = "comment_created",
                                assertions = listOf(AssertionSpec.Oracle("/test/comments/{last_id}", null, null, null)),
                            ),
                        ),
                )

            f.runner().run(campaign)

            f.evidence.assertionList
                .map { it.expected }
                .sorted() shouldBe listOf("/test/comments/c5", "/test/comments/c7")
            f.agents
                .callsFor("comment")
                .map { it.context.templates.lastId }
                .toSet() shouldBe setOf("t1")
        }

    @Test
    fun `a waiter sees an event emitted several steps earlier`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "ok", objectId = call.scenarioStep) }
            val campaign =
                campaign(
                    steps =
                        listOf(
                            step("announce", admin(), emits = "announcement_created"),
                            step("ticket", employees("IT", nth = 1), emits = "ticket_created"),
                            step("read", employees("HR"), waitFor = "announcement_created", waitTimeout = 1.seconds),
                        ),
                )

            val summary = f.runner().run(campaign)

            summary.outcome shouldBe RunOutcome.PASSED
            f.agents
                .callsFor("read")
                .map { it.context.templates.lastId }
                .toSet() shouldBe setOf("announce")
            currentTime shouldBe 0
        }
}
