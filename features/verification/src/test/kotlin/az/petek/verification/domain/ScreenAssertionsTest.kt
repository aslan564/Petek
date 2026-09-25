package az.petek.verification.domain

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.AssertionSpec.Count
import az.petek.campaign.domain.AssertionSpec.HttpStatus
import az.petek.campaign.domain.AssertionSpec.NotVisible
import az.petek.campaign.domain.AssertionSpec.VisibleText
import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateRenderer
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.verification.testing.FakeTemplateRenderer
import az.petek.verification.testing.ScriptedSession
import az.petek.verification.testing.SimpleJsonFieldSelector
import az.petek.verification.testing.assertionInput
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class ScreenAssertionsTest {
    private val clock = FakeHarnessClock()
    private val session = ScriptedSession(clock)
    private val evaluator = DefaultAssertionEvaluator(FakeTargetOracle(), FakeTemplateRenderer(), SimpleJsonFieldSelector(), clock)

    private suspend fun evaluateOne(spec: AssertionSpec) = evaluator.evaluate(listOf(spec), assertionInput(session)).single()

    @Test
    fun `not_visible passes when the text is not on screen`() =
        runTest {
            val result = evaluateOne(NotVisible(text = "Approve", selector = null))

            result.verdict shouldBe Verdict.PASSED
            result.source shouldBe EvidenceSource.RECEIVER
            result.expected shouldBe "text \"Approve\" not visible"
            result.observed shouldBe "not visible"
        }

    @Test
    fun `not_visible fails when the text is on screen`() =
        runTest {
            session.fake.visibleTexts += "Approve"

            val result = evaluateOne(NotVisible(text = "Approve", selector = null))

            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "visible: text \"Approve\""
        }

    @Test
    fun `not_visible renders the selector and fails when that element is visible`() =
        runTest {
            session.fake.visibleSelectors += "[data-testid=\"ticket-approve\"][data-id=\"42\"]"

            val result = evaluateOne(NotVisible(text = null, selector = "[data-testid=\"ticket-approve\"][data-id=\"{last_id}\"]"))

            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "visible: selector `[data-testid=\"ticket-approve\"][data-id=\"42\"]`"
        }

    @Test
    fun `not_visible with text and selector requires both to be hidden`() =
        runTest {
            session.fake.visibleSelectors += "#approve"

            val both = evaluateOne(NotVisible(text = "Approve", selector = "#approve"))
            session.fake.visibleSelectors.clear()
            val neither = evaluateOne(NotVisible(text = "Approve", selector = "#approve"))

            both.verdict shouldBe Verdict.FAILED
            both.expected shouldBe "text \"Approve\" and selector `#approve` not visible"
            neither.verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `not_visible without text and selector fails instead of passing vacuously`() =
        runTest {
            val result = evaluateOne(NotVisible(text = null, selector = null))

            result.verdict shouldBe Verdict.FAILED
            result.note shouldBe "not_visible needs a text or a selector"
        }

    @Test
    fun `count compares the number of matching elements with a rendered selector`() =
        runTest {
            session.fake.counts["[data-testid=\"ticket-item\"][data-id=\"42\"]"] = 1
            session.fake.counts["[data-testid=\"notification-item\"]"] = 2

            val results =
                evaluator.evaluate(
                    listOf(
                        Count("[data-testid=\"ticket-item\"][data-id=\"{last_id}\"]", 1),
                        Count("[data-testid=\"notification-item\"]", 3),
                    ),
                    assertionInput(session),
                )

            results[0].verdict shouldBe Verdict.PASSED
            results[0].source shouldBe EvidenceSource.RECEIVER
            results[0].expected shouldBe "count of `[data-testid=\"ticket-item\"][data-id=\"42\"]` = 1"
            results[0].observed shouldBe "1"
            results[1].verdict shouldBe Verdict.FAILED
            results[1].observed shouldBe "2"
        }

    @Test
    fun `count of zero matches an absent element`() =
        runTest {
            evaluateOne(Count("[data-testid=\"ticket-error\"]", 0)).verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `every browser assertion fails with a note when the actor has no session`() =
        runTest {
            val specs =
                listOf(
                    VisibleText("x", 1.seconds),
                    NotVisible(text = "x", selector = null),
                    Count("#x", 1),
                    HttpStatus("/api/tickets/{last_id}", "GET", 200),
                )

            val results = evaluator.evaluate(specs, assertionInput(session = null))

            results.map { it.verdict } shouldContainExactly List(4) { Verdict.FAILED }
            results.map { it.note } shouldContainExactly List(4) { "no browser session for this actor" }
            results[3].expected shouldBe "GET /api/tickets/42 -> 200"
            results[3].source shouldBe EvidenceSource.ORACLE
        }

    @Test
    fun `a browser error fails that assertion with a note and later assertions still run`() =
        runTest {
            session.failure = BrowserActionException("Target page, context or browser has been closed")

            val results =
                evaluator.evaluate(
                    listOf(Count("#x", 1), NotVisible(text = "Approve", selector = null), AssertionSpec.LatencyMax(1.seconds)),
                    assertionInput(session),
                )

            results.map { it.verdict } shouldContainExactly List(3) { Verdict.FAILED }
            results[0].note shouldBe "count check failed: BrowserActionException: Target page, context or browser has been closed"
            results[1].note!! shouldContain "not_visible check failed"
            results[0].expected shouldBe "count of `#x` = 1"
        }

    @Test
    fun `a template error fails that assertion with a note and shows the raw template`() =
        runTest {
            session.fake.visibleTexts += "ok"

            val results =
                evaluator.evaluate(
                    listOf(VisibleText("Salam {self.nickname}", 1.seconds), VisibleText("ok", 1.seconds)),
                    assertionInput(session),
                )

            results[0].verdict shouldBe Verdict.FAILED
            results[0].note!! shouldContain "template error"
            results[0].note!! shouldContain "{self.nickname}"
            results[0].expected shouldBe "\"Salam {self.nickname}\" visible within 1000 ms"
            results[0].latency.shouldBeNull()
            results[1].verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `an unresolved last_id fails instead of probing a wrong URL`() =
        runTest {
            val input = assertionInput(session, templates = TemplateContext(null, emptyMap(), emptyMap()))

            val result = evaluator.evaluate(listOf(HttpStatus("/api/tickets/{last_id}/approve", "POST", 403)), input).single()

            result.verdict shouldBe Verdict.FAILED
            result.note!! shouldContain "template error"
            session.fake.actions.none { it.startsWith("request") } shouldBe true
        }

    @Test
    fun `cancelling the caller is never turned into a verdict`() =
        runTest {
            val calls = CopyOnWriteArrayList<String>()
            val stuck =
                object : BrowserSession by FakeBrowserSession("a01") {
                    override suspend fun count(selector: String): Int {
                        calls += selector
                        awaitCancellation()
                    }
                }
            var results: List<AssertionResult>? = null

            // UNDISPATCHED runs the evaluation until it suspends inside the browser call, like a watchdog would find it.
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    results = evaluator.evaluate(listOf(Count("#x", 1), Count("#y", 1)), assertionInput(stuck))
                }
            job.cancelAndJoin()

            job.isCancelled shouldBe true
            results.shouldBeNull()
            calls shouldContainExactly listOf("#x")
        }

    @Test
    fun `a cancellation exception leaking out of the browser while the caller is active fails only that check`() =
        runTest {
            session.failure = CancellationException("nested timeout inside the adapter")

            val results = evaluator.evaluate(listOf(Count("#x", 1), NotVisible("Approve", null)), assertionInput(session))

            results.map { it.verdict } shouldContainExactly List(2) { Verdict.FAILED }
            results[0].note shouldBe "count check failed: CancellationException: nested timeout inside the adapter"
            results[0].expected shouldBe "count of `#x` = 1"
        }

    @Test
    fun `a renderer failing with an unexpected exception fails that assertion instead of throwing`() =
        runTest {
            val broken =
                object : TemplateRenderer by FakeTemplateRenderer() {
                    override fun render(
                        template: String,
                        context: TemplateContext,
                    ): String = if ('{' in template) error("renderer bug") else template
                }
            val evaluator = DefaultAssertionEvaluator(FakeTargetOracle(), broken, SimpleJsonFieldSelector(), clock)
            session.fake.counts["#x"] = 1

            val results =
                evaluator.evaluate(listOf(Count("#{last_id}", 1), Count("#x", 1)), assertionInput(session))

            results[0].verdict shouldBe Verdict.FAILED
            results[0].note shouldBe "count check failed: IllegalStateException: renderer bug"
            results[0].expected shouldBe "count of `#{last_id}` = 1"
            results[1].verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `results come back in the order of the specs`() =
        runTest {
            session.fake.visibleTexts += "a"
            val specs = listOf(Count("#x", 0), VisibleText("a", 1.seconds), NotVisible("b", null), AssertionSpec.OnlyOneSucceeds())

            val results = evaluator.evaluate(specs, assertionInput(session))

            results.map { it.spec } shouldContainExactly specs
        }

    @Test
    fun `only_one_succeeds inside a per-actor evaluation is skipped as group-level`() =
        runTest {
            val result = evaluateOne(AssertionSpec.OnlyOneSucceeds())

            result.verdict shouldBe Verdict.SKIPPED
            result.source shouldBe EvidenceSource.SENDER
            result.note!! shouldContain "group-level"
        }

    @Test
    fun `an empty assertion list yields no results`() =
        runTest {
            evaluator.evaluate(emptyList(), assertionInput(session)) shouldBe emptyList()
        }
}
