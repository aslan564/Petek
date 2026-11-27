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

package az.petek.verification.domain

import az.petek.browser.domain.TextWatch
import az.petek.campaign.domain.AssertionSpec.LatencyMax
import az.petek.campaign.domain.AssertionSpec.VisibleText
import az.petek.core.testing.FakeHarnessClock
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

/**
 * Delivery measured from the write and seen by a watch the receiver started before it (Faza 24.10): the emitter's own
 * request is t0, the moment the receiver's page showed the text is t1, whatever the agents did in between.
 */
class WatchedDeliveryTest {
    private val clock = FakeHarnessClock()
    private val session = ScriptedSession(clock)
    private val evaluator = DefaultAssertionEvaluator(FakeTargetOracle(), FakeTemplateRenderer(), SimpleJsonFieldSelector(), clock)

    private val announcement = "Sabah 10:00 ümumi iclas"
    private val write = "the write POST /api/announcements -> 201"

    @Test
    fun `a slow delivery during the emitter's long answer is not hidden behind the publish`() =
        runTest {
            // Written at 0; the text reaches the receiver 3 s later; the emitter's agent publishes the event at 4 s.
            val written = clock.now()
            clock.advance(4.seconds)
            val watch = WatchedText(announcement, TextWatch.Seen(written + 3.seconds))

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(1.seconds)),
                    assertionInput(session, eventTime = EventTime.at(written, write), watch = watch),
                )

            results[0].verdict shouldBe Verdict.PASSED
            results[0].latency shouldBe 3.seconds
            results[0].note.shouldBeNull()
            results[1].verdict shouldBe Verdict.FAILED
            results[1].note shouldBe "3000 ms exceeds 1000 ms"
            // The watch answered: the page was neither waited on nor checked again.
            session.waits.shouldBeEmpty()
            session.immediateChecks.shouldBeEmpty()
        }

    @Test
    fun `a text the watch saw appear is timed from the write`() =
        runTest {
            val written = clock.now()
            clock.advance(6.seconds)
            val watch = WatchedText(announcement, TextWatch.Seen(written + 250.milliseconds))

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(1.seconds)),
                    assertionInput(session, eventTime = EventTime.at(written, write), watch = watch),
                )

            results.map { it.verdict } shouldContainExactly listOf(Verdict.PASSED, Verdict.PASSED)
            results[0].observed shouldBe "seen after 250 ms"
            results[1].observed shouldBe "250 ms"
        }

    @Test
    fun `a text seen only after its window fails even though the watch saw it`() =
        runTest {
            val written = clock.now()
            clock.advance(8.seconds)
            val watch = WatchedText(announcement, TextWatch.Seen(written + 6.seconds))

            val result =
                evaluator
                    .evaluate(
                        listOf(VisibleText(announcement, 5.seconds)),
                        assertionInput(session, eventTime = EventTime.at(written, write), watch = watch),
                    ).single()

            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "seen after 6000 ms"
            result.note shouldBe "seen only after the 5000 ms window"
        }

    @Test
    fun `a text the page showed before the write proves no delivery`() =
        runTest {
            val written = clock.now()
            session.fake.visibleTexts += announcement

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(1.seconds)),
                    assertionInput(
                        session,
                        eventTime = EventTime.at(written, write),
                        watch = WatchedText(announcement, TextWatch.WasThere),
                    ),
                )

            results[0].verdict shouldBe Verdict.INCONCLUSIVE
            results[0].observed shouldBe "visible before the change was written"
            results[0].latency.shouldBeNull()
            results[0].note!! shouldStartWith "stale_text: the receiver's page showed \"$announcement\" already"
            results[0].note!! shouldContain write
            results[1].verdict shouldBe Verdict.INCONCLUSIVE
            results[1].note shouldBe "no latency measured: the preceding visible_text proves no delivery"
            session.immediateChecks.shouldBeEmpty()
        }

    @Test
    fun `a watch that has not seen the text yet waits for the rest of the window from the write`() =
        runTest {
            val written = clock.now()
            clock.advance(1.seconds)
            session.appearsAfter[announcement] = 400.milliseconds

            val result =
                evaluator
                    .evaluate(
                        listOf(VisibleText(announcement, 5.seconds)),
                        assertionInput(
                            session,
                            eventTime = EventTime.at(written, write),
                            watch = WatchedText(announcement, TextWatch.NotYet),
                        ),
                    ).single()

            session.immediateChecks.shouldBeEmpty()
            session.waits shouldContainExactly listOf(announcement to 4.seconds)
            result.verdict shouldBe Verdict.PASSED
            result.latency shouldBe 1400.milliseconds
            result.note.shouldBeNull()
        }

    @Test
    fun `a watch for another text is not used`() =
        runTest {
            val written = clock.now()
            clock.advance(2.seconds)
            session.fake.visibleTexts += announcement

            val result =
                evaluator
                    .evaluate(
                        listOf(VisibleText(announcement, 5.seconds)),
                        assertionInput(
                            session,
                            eventTime = EventTime.at(written, write),
                            watch = WatchedText("Başqa mətn", TextWatch.WasThere),
                        ),
                    ).single()

            result.verdict shouldBe Verdict.PASSED
            result.latency shouldBe 2.seconds
            result.note shouldBe "latency is an upper bound: the text was visible already when first checked"
        }

    @Test
    fun `without a watch a text visible at the first look is only an upper bound`() =
        runTest {
            val written = clock.now()
            clock.advance(3.seconds)
            session.fake.visibleTexts += announcement

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(4.seconds), LatencyMax(2.seconds)),
                    assertionInput(session, eventTime = EventTime.at(written, write)),
                )

            results[0].latency shouldBe 3.seconds
            results[1].verdict shouldBe Verdict.PASSED
            results[2].verdict shouldBe Verdict.INCONCLUSIVE
            results[2].note shouldBe
                "3000 ms exceeds 2000 ms, but it is only an upper bound (the text was visible already when first checked)"
            session.waits.shouldBeEmpty()
        }

    @Test
    fun `when the write was not seen the delay lies between the publish and the start of the action`() =
        runTest {
            // The emitter's action began at 0 and published at 3 s without a request the page showed; seen at 3.2 s.
            val actionStart = clock.now()
            val published = actionStart + 3.seconds
            clock.advance(4.seconds)
            val time = EventTime(published, earliest = actionStart, latest = published, source = "the publish (no write seen)")
            val watch = WatchedText(announcement, TextWatch.Seen(published + 200.milliseconds))

            val results =
                evaluator.evaluate(
                    listOf(
                        VisibleText(announcement, 5.seconds),
                        LatencyMax(5.seconds),
                        LatencyMax(1.seconds),
                        LatencyMax(100.milliseconds),
                    ),
                    assertionInput(session, eventTime = time, watch = watch),
                )

            results[0].latency shouldBe 200.milliseconds
            results[0].note shouldBe
                "the delay may be up to 3200 ms (t0 is the publish (no write seen), and the change was written after the action began)"
            results[1].verdict shouldBe Verdict.PASSED
            results[2].verdict shouldBe Verdict.INCONCLUSIVE
            results[2].note shouldBe
                "200 ms is within 1000 ms, but the delay may be up to 3200 ms (t0 is the publish (no write seen)), " +
                "so the limit cannot be confirmed"
            results[3].verdict shouldBe Verdict.FAILED
            results[3].note shouldBe "200 ms exceeds 100 ms"
        }

    @Test
    fun `when the action sent several writes the first one is t0 and the latency an upper bound`() =
        runTest {
            val first = clock.now()
            val published = first + 2.seconds
            clock.advance(3.seconds)
            val time = EventTime(first, earliest = first, latest = published, source = "the first of several writes")
            val watch = WatchedText(announcement, TextWatch.Seen(first + 1500.milliseconds))

            val results =
                evaluator.evaluate(
                    listOf(VisibleText(announcement, 5.seconds), LatencyMax(2.seconds), LatencyMax(1.seconds)),
                    assertionInput(session, eventTime = time, watch = watch),
                )

            results[0].latency shouldBe 1500.milliseconds
            results[0].note shouldBe "latency is an upper bound: t0 is the first of several writes"
            results[1].verdict shouldBe Verdict.PASSED
            results[2].verdict shouldBe Verdict.INCONCLUSIVE
            results[2].note shouldBe "1500 ms exceeds 1000 ms, but it is only an upper bound (t0 is the first of several writes)"
        }

    @Test
    fun `a text that appeared before t0 counts as delivered at once`() =
        runTest {
            val actionStart = clock.now()
            val published = actionStart + 3.seconds
            clock.advance(4.seconds)
            val time = EventTime(published, earliest = actionStart, latest = published, source = "the publish (no write seen)")
            val watch = WatchedText(announcement, TextWatch.Seen(actionStart + 1.seconds))

            val result =
                evaluator
                    .evaluate(listOf(VisibleText(announcement, 5.seconds)), assertionInput(session, eventTime = time, watch = watch))
                    .single()

            result.verdict shouldBe Verdict.PASSED
            result.latency shouldBe 0.seconds
            result.note!! shouldContain "the text appeared 2000 ms before t0, counted as 0 ms"
            result.note shouldContain "the delay may be up to 1000 ms"
        }
}
