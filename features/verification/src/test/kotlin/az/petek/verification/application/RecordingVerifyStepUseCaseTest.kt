package az.petek.verification.application

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.AssertionSpec.Count
import az.petek.campaign.domain.AssertionSpec.HttpStatus
import az.petek.campaign.domain.AssertionSpec.LatencyMax
import az.petek.campaign.domain.AssertionSpec.NotVisible
import az.petek.campaign.domain.AssertionSpec.OnlyOneSucceeds
import az.petek.campaign.domain.AssertionSpec.Oracle
import az.petek.campaign.domain.AssertionSpec.VisibleText
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.RequestPattern
import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.testing.FakeHarnessClock
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionEvaluator
import az.petek.verification.domain.AssertionInput
import az.petek.verification.domain.AssertionResult
import az.petek.verification.domain.DefaultAssertionEvaluator
import az.petek.verification.domain.RaceEvidence
import az.petek.verification.testing.FakeTemplateRenderer
import az.petek.verification.testing.ScriptedSession
import az.petek.verification.testing.SimpleJsonFieldSelector
import az.petek.verification.testing.assertionInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RecordingVerifyStepUseCaseTest {
    private val clock = FakeHarnessClock()
    private val session = ScriptedSession(clock)
    private val oracle = FakeTargetOracle()
    private val evidence = InMemoryEvidence()
    private val store = InMemoryArtifactStore()

    private fun useCase(
        target: FakeTargetOracle = oracle,
        recorder: EvidenceRecorder = evidence,
        artifacts: ArtifactStore = store,
        evaluator: AssertionEvaluator = DefaultAssertionEvaluator(target, FakeTemplateRenderer(), SimpleJsonFieldSelector(), clock),
    ) = RecordingVerifyStepUseCase(evaluator, recorder, artifacts, SequentialIdGenerator())

    private fun artifact(id: ArtifactId): ArtifactRecord = evidence.artifactList.single { it.artifactId == id }

    private fun content(id: ArtifactId): String = store.contents.getValue(id).decodeToString()

    private fun assertEveryVerdictIsBacked(records: List<AssertionRecord>) {
        val recorded = evidence.artifactList.map { it.artifactId }.toSet()
        records.forEach { record ->
            record.artifactIds.shouldNotBeEmpty()
            record.artifactIds.forEach { id -> (id in recorded) shouldBe true }
        }
    }

    // --- evidence per source ------------------------------------------------------------------------------------------

    @Test
    fun `receiver results share one screenshot of the actor's session`() =
        runTest {
            session.fake.visibleTexts += "Salam"
            session.fake.counts["[data-testid=\"notification-item\"]"] = 1

            val records =
                useCase().verifyActor(
                    listOf(
                        VisibleText("Salam", 1.seconds),
                        Count("[data-testid=\"notification-item\"]", 1),
                        NotVisible("Approve", null),
                    ),
                    assertionInput(session),
                )

            session.screenshots.get() shouldBe 1
            val screenshot = evidence.artifactList.single()
            screenshot.type shouldBe ArtifactType.SCREENSHOT
            screenshot.runId shouldBe RunId("run_test")
            screenshot.stepId shouldBe StepId("stp_read")
            screenshot.relativePath shouldContain "/a01/"
            content(screenshot.artifactId) shouldBe "png:a01:about:blank"
            records.map { it.artifactIds } shouldContainExactly List(3) { listOf(screenshot.artifactId) }
        }

    @Test
    fun `records carry the evaluated values and are stored in the order of the specs`() =
        runTest {
            val t0 = clock.now()
            clock.advance(1.seconds)
            session.appearsAfter["Sabah 10:00 ümumi iclas"] = 500.milliseconds

            val records =
                useCase().verifyActor(
                    listOf(VisibleText("Sabah 10:00 ümumi iclas", 5.seconds), LatencyMax(1.seconds)),
                    assertionInput(session, eventEmittedAt = t0),
                )

            evidence.assertionList shouldContainExactly records
            val (visible, latency) = records
            visible.stepId shouldBe StepId("stp_read")
            visible.runId shouldBe RunId("run_test")
            visible.agentId shouldBe AgentId("a01")
            visible.scenarioStep shouldBe "read_announce"
            visible.type shouldBe "visible_text"
            visible.source shouldBe EvidenceSource.RECEIVER
            visible.verdict shouldBe Verdict.PASSED
            visible.expected shouldBe "\"Sabah 10:00 ümumi iclas\" visible within 5000 ms"
            visible.observed shouldBe "seen after 1500 ms"
            visible.latencyMs shouldBe 1500L
            visible.note.shouldBeNull()
            latency.type shouldBe "latency_max"
            latency.source shouldBe EvidenceSource.HARNESS
            latency.verdict shouldBe Verdict.FAILED
            latency.observed shouldBe "1500 ms"
            latency.note shouldBe "1500 ms exceeds 1000 ms"
            latency.latencyMs.shouldBeNull()
            // latency_max is backed by the same screen as the visible_text it measures.
            latency.artifactIds shouldBe visible.artifactIds
            session.screenshots.get() shouldBe 1
        }

    @Test
    fun `an oracle result stores the raw answer as an ORACLE artifact without a screenshot`() =
        runTest {
            val body = """{"id": "42", "status": "in_progress"}"""
            oracle.respond("/test/tickets/42", body)

            val record =
                useCase()
                    .verifyActor(
                        listOf(Oracle("/test/tickets/{last_id}", "status", "in_progress", null)),
                        assertionInput(session),
                    ).single()

            session.screenshots.get() shouldBe 0
            record.verdict shouldBe Verdict.PASSED
            record.source shouldBe EvidenceSource.ORACLE
            val stored = artifact(record.artifactIds.single())
            stored.type shouldBe ArtifactType.ORACLE
            content(stored.artifactId) shouldBe body
        }

    @Test
    fun `an http_status result stores the status and body as an HTTP artifact`() =
        runTest {
            session.fake.httpResponses["POST /api/tickets/42/approve"] = HttpProbeResult(403, """{"error":"forbidden"}""")

            val record =
                useCase().verifyActor(listOf(HttpStatus("/api/tickets/{last_id}/approve", "POST", 403)), assertionInput(session)).single()

            session.screenshots.get() shouldBe 0
            record.verdict shouldBe Verdict.PASSED
            val stored = artifact(record.artifactIds.single())
            stored.type shouldBe ArtifactType.HTTP
            content(stored.artifactId) shouldBe """403 {"error":"forbidden"}"""
        }

    @Test
    fun `a mixed step links the screen to screen checks and each answer to its own check`() =
        runTest {
            session.fake.visibleTexts += "Approve"
            oracle.respond("/test/tickets/42", """{"status": "open"}""")
            session.fake.httpResponses["POST /api/tickets/42/approve"] = HttpProbeResult(403, "")

            val records =
                useCase().verifyActor(
                    listOf(
                        NotVisible("Approve", null),
                        Oracle("/test/tickets/{last_id}", "status", "open", null),
                        HttpStatus("/api/tickets/{last_id}/approve", "POST", 403),
                    ),
                    assertionInput(session),
                )

            evidence.artifactList.map { it.type } shouldContainExactly
                listOf(ArtifactType.SCREENSHOT, ArtifactType.ORACLE, ArtifactType.HTTP)
            records.map { record -> record.artifactIds.map { artifact(it).type } } shouldContainExactly
                listOf(listOf(ArtifactType.SCREENSHOT), listOf(ArtifactType.ORACLE), listOf(ArtifactType.HTTP))
            records.map { it.verdict } shouldContainExactly listOf(Verdict.FAILED, Verdict.PASSED, Verdict.PASSED)
        }

    // --- no verdict without evidence ------------------------------------------------------------------------------------

    @Test
    fun `results without a session are backed by one harness log that explains them`() =
        runTest {
            val records =
                useCase().verifyActor(listOf(VisibleText("Salam", 1.seconds), Count("#x", 1)), assertionInput(session = null))

            records.map { it.verdict } shouldContainExactly listOf(Verdict.FAILED, Verdict.FAILED)
            val log = evidence.artifactList.single()
            log.type shouldBe ArtifactType.LOG
            records.map { it.artifactIds } shouldContainExactly List(2) { listOf(log.artifactId) }
            val text = content(log.artifactId)
            text shouldContain "[1] visible_text FAILED (RECEIVER)"
            text shouldContain "[2] count FAILED (RECEIVER)"
            text shouldContain "note: no browser session for this actor"
            text shouldContain "scenario step read_announce"
        }

    @Test
    fun `a skipped oracle check is backed by the harness log`() =
        runTest {
            val records =
                useCase(target = FakeTargetOracle(isAvailable = false))
                    .verifyActor(
                        listOf(Oracle("/test/announcements/{last_id}/receipts", null, null, "{self.email}")),
                        assertionInput(session),
                    )

            val record = records.single()
            record.verdict shouldBe Verdict.SKIPPED
            record.note shouldBe "no test API"
            artifact(record.artifactIds.single()).type shouldBe ArtifactType.LOG
            content(record.artifactIds.single()) shouldContain "oracle SKIPPED (ORACLE)"
        }

    @Test
    fun `an empty oracle body is not treated as evidence`() =
        runTest {
            oracle.responses["/test/tickets/42"] = OracleResponse(404, null, "")

            val record = useCase().verifyActor(listOf(Oracle("/test/tickets/42", "status", "open", null)), assertionInput(session)).single()

            record.verdict shouldBe Verdict.FAILED
            artifact(record.artifactIds.single()).type shouldBe ArtifactType.LOG
            content(record.artifactIds.single()) shouldContain "observed: HTTP 404"
        }

    @Test
    fun `a failed screenshot falls back to the harness log and says why`() =
        runTest {
            session.fake.visibleTexts += "Salam"
            session.screenshotFailure = BrowserActionException("Target crashed")
            oracle.respond("/test/tickets/42", """{"status": "open"}""")

            val records =
                useCase().verifyActor(
                    listOf(VisibleText("Salam", 1.seconds), Oracle("/test/tickets/42", "status", "open", null)),
                    assertionInput(session),
                )

            val (visible, fromOracle) = records
            visible.verdict shouldBe Verdict.PASSED
            visible.note shouldBe "screenshot unavailable: BrowserActionException: Target crashed"
            artifact(visible.artifactIds.single()).type shouldBe ArtifactType.LOG
            content(visible.artifactIds.single()) shouldContain "screenshot unavailable"
            fromOracle.note.shouldBeNull()
            artifact(fromOracle.artifactIds.single()).type shouldBe ArtifactType.ORACLE
        }

    @Test
    fun `a cancellation exception leaking out of the screenshot call is a failed screenshot, not an abort`() =
        runTest {
            session.fake.visibleTexts += "Salam"
            session.screenshotFailure = CancellationException("nested timeout inside the adapter")

            val record = useCase().verifyActor(listOf(VisibleText("Salam", 1.seconds)), assertionInput(session)).single()

            record.verdict shouldBe Verdict.PASSED
            record.note shouldBe "screenshot unavailable: CancellationException: nested timeout inside the adapter"
            artifact(record.artifactIds.single()).type shouldBe ArtifactType.LOG
        }

    @Test
    fun `every recorded assertion links to at least one recorded artifact whatever its verdict`() =
        runTest {
            val t0 = clock.now()
            session.appearsAfter["Salam"] = 100.milliseconds
            oracle.respond("/test/tickets/42", """{"status": "open"}""")
            val specs =
                listOf(
                    VisibleText("Salam", 1.seconds),
                    LatencyMax(1.seconds),
                    VisibleText("Missing", 1.seconds),
                    NotVisible(null, "#approve"),
                    Count("#x", 2),
                    Oracle("/test/tickets/{last_id}", "status", "approved", null),
                    Oracle("/test/tickets/{unknown}", null, null, null),
                    HttpStatus("/api/x", "GET", 200),
                )

            val withSession = useCase().verifyActor(specs, assertionInput(session, eventEmittedAt = t0))
            val withoutSession = useCase().verifyActor(specs, assertionInput(session = null, agentId = AgentId("a02")))
            val skipped = useCase(target = FakeTargetOracle(isAvailable = false)).verifyActor(specs, assertionInput(session))

            (withSession + withoutSession + skipped).map { it.verdict }.toSet() shouldBe Verdict.entries.toSet()
            assertEveryVerdictIsBacked(withSession + withoutSession + skipped)
            evidence.assertionList shouldHaveSize specs.size * 3
        }

    @Test
    fun `artifacts are recorded before the assertions that reference them`() =
        runTest {
            val recorder = OrderedRecorder(evidence)
            session.fake.visibleTexts += "Salam"
            oracle.respond("/test/tickets/42", """{"status": "open"}""")

            useCase(recorder = recorder).verifyActor(
                listOf(VisibleText("Salam", 1.seconds), Oracle("/test/tickets/42", "status", "open", null), Count("#x", 1)),
                assertionInput(session = session),
            )

            val seen = mutableSetOf<String>()
            recorder.calls.forEach { call ->
                when {
                    call.startsWith("artifact ") -> seen += call.removePrefix("artifact ")
                    else -> call.substringAfter(" refs ").split(",").forEach { id -> (id in seen) shouldBe true }
                }
            }
            recorder.calls.count { it.startsWith("assertion ") } shouldBe 3
        }

    @Test
    fun `storage failures propagate instead of recording an unbacked verdict`() =
        runTest {
            val failing =
                object : ArtifactStore by InMemoryArtifactStore() {
                    override suspend fun write(
                        runId: RunId,
                        stepId: StepId,
                        owner: String,
                        type: ArtifactType,
                        bytes: ByteArray,
                    ): ArtifactRecord = throw IOException("disk full")
                }
            session.fake.visibleTexts += "Salam"

            shouldThrow<IOException> {
                useCase(artifacts = failing).verifyActor(listOf(VisibleText("Salam", 1.seconds)), assertionInput(session))
            }
            evidence.assertionList.shouldBeEmpty()
        }

    @Test
    fun `a result carrying both a screen and a raw answer links both`() =
        runTest {
            val scripted =
                object : AssertionEvaluator {
                    override suspend fun evaluate(
                        specs: List<AssertionSpec>,
                        input: AssertionInput,
                    ) = specs.map { AssertionResult(it, Verdict.PASSED, EvidenceSource.RECEIVER, "e", "o", null, null, "raw log") }

                    override fun evaluateOnlyOneSucceeds(results: List<ActorResult>) = error("not used")
                }

            val record = useCase(evaluator = scripted).verifyActor(listOf(Count("#x", 1)), assertionInput(session)).single()

            record.artifactIds.map { artifact(it).type } shouldContainExactly listOf(ArtifactType.SCREENSHOT, ArtifactType.LOG)
            content(record.artifactIds[1]) shouldBe "raw log"
        }

    // --- actor vs group -------------------------------------------------------------------------------------------------

    @Test
    fun `verifyActor leaves group-level assertions to verifyGroup`() =
        runTest {
            val records = useCase().verifyActor(listOf(OnlyOneSucceeds(), Count("#x", 0)), assertionInput(session))

            records.map { it.type } shouldContainExactly listOf("count")
        }

    @Test
    fun `verifyActor with nothing to check records nothing and takes no screenshot`() =
        runTest {
            useCase().verifyActor(listOf(OnlyOneSucceeds()), assertionInput(session)).shouldBeEmpty()
            useCase().verifyActor(emptyList(), assertionInput(session)).shouldBeEmpty()

            session.screenshots.get() shouldBe 0
            evidence.assertionList.shouldBeEmpty()
            evidence.artifactList.shouldBeEmpty()
        }

    @Test
    fun `verifyGroup judges only_one_succeeds once and keeps every actor outcome as evidence`() =
        runTest {
            val results =
                listOf(
                    ActorResult(AgentId("a02"), succeeded = true, summary = "approved"),
                    ActorResult(AgentId("a03"), succeeded = true, summary = "approved"),
                )

            val records =
                useCase().verifyGroup(
                    listOf(VisibleText("Salam", 1.seconds), OnlyOneSucceeds()),
                    assertionInput(session = null, agentId = null, scenarioStep = "race_approve"),
                    results,
                )

            val record = records.single()
            record.type shouldBe "only_one_succeeds"
            record.source shouldBe EvidenceSource.SENDER
            record.verdict shouldBe Verdict.FAILED
            record.observed shouldBe "a02 succeeded; a03 succeeded"
            record.agentId.shouldBeNull()
            record.scenarioStep shouldBe "race_approve"
            val stored = artifact(record.artifactIds.single())
            stored.type shouldBe ArtifactType.LOG
            stored.relativePath shouldContain "/harness/"
            Json
                .parseToJsonElement(content(stored.artifactId))
                .jsonObject["winners"]!!
                .jsonPrimitive.int shouldBe 2
            evidence.assertionList shouldContainExactly records
        }

    @Test
    fun `verifyGroup with a single winner passes`() =
        runTest {
            val records =
                useCase().verifyGroup(
                    listOf(OnlyOneSucceeds()),
                    assertionInput(session = null, agentId = null),
                    listOf(ActorResult(AgentId("a02"), true, "approved"), ActorResult(AgentId("a03"), false, "409")),
                )

            records.single().verdict shouldBe Verdict.PASSED
            assertEveryVerdictIsBacked(records)
        }

    @Test
    fun `verifyGroup judges the race with its request pattern and keeps the oracle answer as its own artifact`() =
        runTest {
            val ticket = """{"id": "42", "status": "approved"}"""
            oracle.respond("/test/tickets/42", ticket)
            val approve = RequestPattern("POST", ".*/approve")
            val won = RaceEvidence.of(approve, listOf(session.fake.mutated("POST", "/tickets/42/approve", 303)))
            val lost = RaceEvidence.of(approve, listOf(session.fake.mutated("POST", "/tickets/42/approve", 409)))
            val spec = OnlyOneSucceeds(approve, OracleCondition("/test/tickets/{last_id}", "status", "approved"))

            val record =
                useCase()
                    .verifyGroup(
                        listOf(spec),
                        assertionInput(session = null, agentId = null, scenarioStep = "race"),
                        listOf(
                            ActorResult(AgentId("a02"), won.succeeded, "approved", won),
                            ActorResult(AgentId("a03"), lost.succeeded, "approved too", lost, lostRace = true),
                        ),
                    ).single()

            record.verdict shouldBe Verdict.PASSED
            record.expected shouldContain "by `POST .*/approve` and GET /test/tickets/42 field `status`"
            record.observed shouldBe "a02 POST /tickets/42/approve -> 303; a03 POST /tickets/42/approve -> 409; oracle: status = approved"
            record.artifactIds.map { artifact(it).type } shouldContainExactly listOf(ArtifactType.LOG, ArtifactType.ORACLE)
            content(record.artifactIds[1]) shouldBe ticket
            assertEveryVerdictIsBacked(listOf(record))
        }

    @Test
    fun `verifyGroup without group-level assertions records nothing`() =
        runTest {
            useCase()
                .verifyGroup(listOf(Count("#x", 1)), assertionInput(session), listOf(ActorResult(AgentId("a02"), true, "ok")))
                .shouldBeEmpty()

            evidence.assertionList.shouldBeEmpty()
            session.screenshots.get() shouldBe 0
        }

    // --- concurrency ------------------------------------------------------------------------------------------------------

    @Test
    fun `actors verifying concurrently each get their own screenshot and records`() {
        val actors = (1..20).map { AgentId.of(it) }
        val sessions = actors.associateWith { ScriptedSession(clock, FakeBrowserSession(it.value, clock)) }
        sessions.values.forEach { it.fake.visibleTexts += "Salam" }
        val verify = useCase()

        val records =
            runBlocking(Dispatchers.Default) {
                actors
                    .map { agent ->
                        async {
                            verify.verifyActor(
                                listOf(VisibleText("Salam", 1.seconds), Count("#x", 0)),
                                assertionInput(sessions.getValue(agent), agentId = agent),
                            )
                        }
                    }.awaitAll()
                    .flatten()
            }

        records shouldHaveSize 40
        evidence.assertionList shouldHaveSize 40
        evidence.artifactList shouldHaveSize 20
        records.forEach { record ->
            val shot = artifact(record.artifactIds.single())
            content(shot.artifactId) shouldBe "png:${record.agentId}:about:blank"
            shot.relativePath shouldContain "/${record.agentId}/"
        }
        sessions.values.forEach { it.screenshots.get() shouldBe 1 }
    }

    /** Records the order of artifact and assertion writes. */
    private class OrderedRecorder(
        private val delegate: InMemoryEvidence,
    ) : EvidenceRecorder by delegate {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun artifact(record: ArtifactRecord) {
            calls += "artifact ${record.artifactId}"
            delegate.artifact(record)
        }

        override suspend fun assertion(record: AssertionRecord) {
            calls += "assertion ${record.type} refs ${record.artifactIds.joinToString(",")}"
            delegate.assertion(record)
        }
    }
}
