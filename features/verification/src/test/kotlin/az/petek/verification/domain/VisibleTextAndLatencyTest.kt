package az.petek.verification.domain

import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.AssertionSpec.LatencyMax
import az.petek.campaign.domain.AssertionSpec.VisibleText
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.verification.testing.FakeTemplateRenderer
import az.petek.verification.testing.ScriptedSession
import az.petek.verification.testing.SimpleJsonFieldSelector
import az.petek.verification.testing.assertionInput
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VisibleTextAndLatencyTest {
    private val clock = FakeHarnessClock()
    private val session = ScriptedSession(clock)
    private val evaluator = DefaultAssertionEvaluator(FakeTargetOracle(), FakeTemplateRenderer(), SimpleJsonFieldSelector(), clock)

    private val announcement = "Sabah 10:00 ümumi iclas"

    @Test
    fun `visible_text with t0 waits only for the rest of the window and measures latency from t0`() =
        runTest {
            val t0 = clock.now()
            clock.advance(1200.milliseconds)
            session.appearsAfter[announcement] = 300.milliseconds

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, t0)).single()

            session.waits shouldContainExactly listOf(announcement to 3800.milliseconds)
            result.verdict shouldBe Verdict.PASSED
            result.source shouldBe EvidenceSource.RECEIVER
            result.latency shouldBe 1500.milliseconds
            result.observed shouldBe "seen after 1500 ms"
            result.expected shouldBe "\"$announcement\" visible within 5000 ms"
            result.note.shouldBeNull()
            result.rawEvidence.shouldBeNull()
        }

    @Test
    fun `visible_text renders templates in the awaited text`() =
        runTest {
            session.fake.visibleTexts += "Salam, Aysel Məmmədova"

            val result = evaluator.evaluate(listOf(VisibleText("Salam, {self.name}", 2.seconds)), assertionInput(session)).single()

            result.verdict shouldBe Verdict.PASSED
            session.waits.map { it.first } shouldContainExactly listOf("Salam, Aysel Məmmədova")
            result.expected shouldContain "Salam, Aysel Məmmədova"
        }

    @Test
    fun `visible_text that never appears fails after the remaining window`() =
        runTest {
            val t0 = clock.now()
            clock.advance(1.seconds)

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, t0)).single()

            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "not seen within 5000 ms"
            result.latency.shouldBeNull()
            session.waits shouldContainExactly listOf(announcement to 4.seconds)
        }

    @Test
    fun `visible_text whose window already elapsed checks once without waiting and marks the latency as an upper bound`() =
        runTest {
            val t0 = clock.now()
            clock.advance(6.seconds)
            session.fake.visibleTexts += announcement

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, t0)).single()

            session.waits.shouldBeEmpty()
            session.immediateChecks shouldContainExactly listOf(announcement)
            result.verdict shouldBe Verdict.PASSED
            result.latency shouldBe 6.seconds
            result.note!! shouldContain "window had elapsed 1000 ms before the check"
            result.note shouldContain "upper bound"
        }

    @Test
    fun `visible_text whose window already elapsed fails at once when the text is absent`() =
        runTest {
            val t0 = clock.now()
            clock.advance(7.seconds)

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, t0)).single()

            session.waits.shouldBeEmpty()
            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "not seen within 5000 ms"
            result.note!! shouldContain "window had elapsed 2000 ms"
            clock.now().monotonicNanos shouldBe 7.seconds.inWholeNanoseconds
        }

    @Test
    fun `visible_text exactly at the deadline never passes a zero timeout to the browser`() =
        runTest {
            val t0 = clock.now()
            clock.advance(5.seconds)
            session.fake.visibleTexts += announcement

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, t0)).single()

            session.waits.shouldBeEmpty()
            session.immediateChecks shouldContainExactly listOf(announcement)
            result.verdict shouldBe Verdict.PASSED
            result.latency shouldBe 5.seconds
        }

    @Test
    fun `visible_text without t0 waits the whole window and reports no latency`() =
        runTest {
            clock.advance(30.seconds)
            session.appearsAfter[announcement] = 2.seconds

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session)).single()

            session.waits shouldContainExactly listOf(announcement to 5.seconds)
            result.verdict shouldBe Verdict.PASSED
            result.latency.shouldBeNull()
            result.observed shouldBe "seen"
        }

    @Test
    fun `visible_text without t0 and without a window checks once`() =
        runTest {
            session.fake.visibleTexts += announcement

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 0.seconds)), assertionInput(session)).single()

            session.waits.shouldBeEmpty()
            result.verdict shouldBe Verdict.PASSED
            result.note!! shouldContain "checked once"
        }

    @Test
    fun `visible_text falls back to the harness clock when the browser reports no observation time`() =
        runTest {
            val browser = FakeBrowserSession("a01", clock = null)
            browser.visibleTexts += announcement
            val t0 = clock.now()
            clock.advance(700.milliseconds)

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(browser, t0)).single()

            result.verdict shouldBe Verdict.PASSED
            result.latency shouldBe 700.milliseconds
        }

    @Test
    fun `visible_text never reports a negative latency`() =
        runTest {
            val browser = FakeBrowserSession("a01", clock = null)
            browser.visibleTexts += announcement
            clock.advance(1.seconds)
            val t0 = clock.now()
            // An event stamped in the future (t0 after now) must not produce a negative latency.
            val future = t0.copy(monotonicNanos = t0.monotonicNanos + 2.seconds.inWholeNanoseconds)

            val result = evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(browser, future)).single()

            result.latency shouldBe 0.seconds
        }

    @Test
    fun `latency_max passes when the preceding visible_text latency is within the limit`() =
        runTest {
            val t0 = clock.now()
            session.appearsAfter[announcement] = 1500.milliseconds

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(2.seconds)),
                    assertionInput(session, t0),
                )

            val latency = results[1]
            latency.verdict shouldBe Verdict.PASSED
            latency.source shouldBe EvidenceSource.HARNESS
            latency.observed shouldBe "1500 ms"
            latency.expected shouldBe "latency <= 2000 ms"
            latency.latency.shouldBeNull()
        }

    @Test
    fun `latency_max equal to the limit passes and above it fails`() =
        runTest {
            val t0 = clock.now()
            session.appearsAfter[announcement] = 1500.milliseconds

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(1500.milliseconds), LatencyMax(1499.milliseconds)),
                    assertionInput(session, t0),
                )

            results[1].verdict shouldBe Verdict.PASSED
            results[2].verdict shouldBe Verdict.FAILED
            results[2].note shouldBe "1500 ms exceeds 1499 ms"
        }

    @Test
    fun `latency_max pairs with the closest preceding visible_text`() =
        runTest {
            val t0 = clock.now()
            session.appearsAfter["first"] = 200.milliseconds
            session.appearsAfter["second"] = 900.milliseconds

            val results =
                evaluator.evaluate(
                    listOf(
                        VisibleText("first", 5.seconds),
                        LatencyMax(1.seconds),
                        VisibleText("second", 5.seconds),
                        LatencyMax(1.seconds),
                    ),
                    assertionInput(session, t0),
                )

            results[1].verdict shouldBe Verdict.PASSED
            results[1].observed shouldBe "200 ms"
            results[2].latency shouldBe 1100.milliseconds
            results[3].verdict shouldBe Verdict.FAILED
            results[3].observed shouldBe "1100 ms"
        }

    @Test
    fun `latency_max without a preceding visible_text fails with no latency measured`() =
        runTest {
            session.fake.visibleTexts += announcement

            val results =
                evaluator.evaluate(
                    listOf(LatencyMax(1.seconds), VisibleText(announcement, 5.seconds)),
                    assertionInput(session, clock.now()),
                )

            results[0].verdict shouldBe Verdict.FAILED
            results[0].note!! shouldStartWith "no latency measured"
            results[0].note!! shouldContain "no preceding visible_text"
            results[1].verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `latency_max after an unseen text fails with no latency measured`() =
        runTest {
            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 1.seconds), LatencyMax(5.seconds)),
                    assertionInput(session, clock.now()),
                )

            results[1].verdict shouldBe Verdict.FAILED
            results[1].observed.shouldBeNull()
            results[1].note shouldBe "no latency measured: the preceding visible_text failed"
        }

    @Test
    fun `latency_max after a visible_text without t0 fails with no latency measured`() =
        runTest {
            session.fake.visibleTexts += announcement

            val results =
                evaluator.evaluate(listOf(VisibleText(announcement, 1.seconds), LatencyMax(5.seconds)), assertionInput(session))

            results[0].verdict shouldBe Verdict.PASSED
            results[1].verdict shouldBe Verdict.FAILED
            results[1].note!! shouldContain "no event time (t0)"
        }

    @Test
    fun `latency_max over an upper-bound latency explains that the real delivery time is unknown`() =
        runTest {
            val t0 = clock.now()
            clock.advance(8.seconds)
            session.fake.visibleTexts += announcement

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(5.seconds)),
                    assertionInput(session, t0),
                )

            results[1].verdict shouldBe Verdict.FAILED
            results[1].note!! shouldContain "only an upper bound"
        }

    @Test
    fun `latency pairing does not leak between evaluate calls`() =
        runTest {
            session.appearsAfter[announcement] = 100.milliseconds
            evaluator.evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, clock.now()))

            val result = evaluator.evaluate(listOf(LatencyMax(5.seconds)), assertionInput(session, clock.now())).single()

            result.verdict shouldBe Verdict.FAILED
            result.note!! shouldContain "no preceding visible_text"
        }
}
