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

package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.FailureReason
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.AgentId
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.admin
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Delivery latency measured from the write, with receivers watching before it (Faza 24.10): a01 is the admin who
 * announces, a02 and a03 the employees who read. The site "delivers" by showing the text on the readers' pages; the
 * admin's agent may keep working long after that, which must not shorten or hide the measured delay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerDeliveryTest {
    private val ok = ActionOutcome(ActionStatus.SUCCEEDED, "ok")
    private val text = "Sabah 10:00 ümumi iclas"
    private val readers = listOf("a02", "a03")

    private fun TestScope.fixture() = RunnerFixture(VirtualClock(testScheduler)).apply { verify.realScreenChecks = true }

    private fun announcing(
        readCheck: List<AssertionSpec> = listOf(AssertionSpec.VisibleText(text, 10.seconds), AssertionSpec.LatencyMax(1.seconds)),
        request: RequestPattern? = null,
    ) = campaign(
        managers = 0,
        employees = 2,
        steps =
            listOf(
                step("announce", admin(), emits = "announcement_created", emitsRequest = request),
                step("read", employees(), waitFor = "announcement_created", assertions = readCheck),
            ),
    )

    /** The admin's page posts, the readers' pages show [text] [deliveredAfter] later, the agent answers [answersAfter] after that. */
    private fun RunnerFixture.announce(
        deliveredAfter: Duration,
        answersAfter: Duration,
        writes: List<Pair<String, Int>> = listOf("/api/announcements" to 201),
    ) {
        agents.script = { call, _ ->
            if (call.scenarioStep.startsWith("announce")) {
                writes.forEachIndexed { i, (path, status) ->
                    if (i > 0) delay(1.seconds)
                    browser.session("a01").mutated("POST", path, status)
                }
                delay(deliveredAfter)
                readers.forEach { browser.session(it).showText(text) }
                delay(answersAfter)
                ActionOutcome(ActionStatus.SUCCEEDED, "announced", objectId = "n1")
            } else {
                ok
            }
        }
    }

    private fun RunnerFixture.checks(type: String) = evidence.assertionList.filter { it.type == type && it.scenarioStep.startsWith("read") }

    @Test
    fun `a delivery slower than the limit fails although the emitter's agent answers even later`() =
        runTest {
            val f = fixture()
            f.announce(deliveredAfter = 3.seconds, answersAfter = 2.seconds)

            val summary = f.runner().run(announcing())

            // Measured from the publish, as before, the text would have been there at once: 0 ms, a false pass.
            f.evidence.receiptList.map { it.receiver.value to it.latencyMs } shouldContainExactly readers.map { it to 3_000L }
            f.checks("visible_text").map { it.verdict }.toSet() shouldBe setOf(Verdict.PASSED)
            f.checks("latency_max").map { it.verdict to it.note }.toSet() shouldBe setOf(Verdict.FAILED to "3000 ms exceeds 1000 ms")
            summary.outcome shouldBe RunOutcome.FAILED
            val event = f.evidence.eventList.single()
            event.payloadJson shouldContain "\"write\":\"POST /api/announcements -> 201\""
            event.t0
                .plusSeconds(5)
                .toString()
                .let { published -> event.payloadJson shouldContain "\"published_at\":\"$published\"" }
            f.step("announce", StepKind.EMIT, "a01").detail!! shouldContain "written by POST /api/announcements -> 201"
            readers.forEach { f.browser.session(it).watched shouldContainExactly listOf("read" to text) }
        }

    @Test
    fun `a fast delivery is measured as it was, however long the emitter's agent keeps working`() =
        runTest {
            val f = fixture()
            f.announce(deliveredAfter = 300.milliseconds, answersAfter = 4.seconds)

            val summary = f.runner().run(announcing())

            f.evidence.receiptList.map { it.latencyMs } shouldContainExactly listOf(300L, 300L)
            f.checks("latency_max").map { it.verdict } shouldContainExactly listOf(Verdict.PASSED, Verdict.PASSED)
            summary.outcome shouldBe RunOutcome.PASSED
        }

    @Test
    fun `the step's own request is the write, not the first request the page sent`() =
        runTest {
            val named = fixture()
            named.announce(300.milliseconds, 1.seconds, writes = listOf("/api/drafts" to 200, "/api/announcements" to 201))

            named.runner().run(announcing(request = RequestPattern("POST", "/api/announcements")))

            named.evidence.receiptList.map { it.latencyMs } shouldContainExactly listOf(300L, 300L)
            named.step("announce", StepKind.EMIT, "a01").detail!! shouldContain "written by POST /api/announcements -> 201"

            val unnamed = fixture()
            unnamed.announce(300.milliseconds, 1.seconds, writes = listOf("/api/drafts" to 200, "/api/announcements" to 201))

            unnamed.runner().run(announcing())

            // Without the request only the first write is known: an upper bound, said so, and the limit is not confirmed.
            unnamed.evidence.receiptList.map { it.latencyMs } shouldContainExactly listOf(1_300L, 1_300L)
            unnamed.step("announce", StepKind.EMIT, "a01").detail!! shouldContain
                "written by POST /api/drafts -> 200 or a later one of 2 accepted requests (emits.request names the write)"
            unnamed.checks("visible_text").map { it.note }.toSet() shouldBe
                setOf("latency is an upper bound: t0 is the first of several writes, POST /api/drafts -> 200")
            unnamed.checks("latency_max").map { it.verdict }.toSet() shouldBe setOf(Verdict.INCONCLUSIVE)
            unnamed.checks("latency_max").first().note!! shouldContain "but it is only an upper bound"
        }

    @Test
    fun `a text already on the readers' pages proves no delivery`() =
        runTest {
            val f = fixture()
            f.browser.configure = { session -> if (session.label in readers) session.showText(text) }
            f.announce(deliveredAfter = 300.milliseconds, answersAfter = 1.seconds)

            val summary = f.runner().run(announcing())

            // Nothing proves a delivery: undecided, not a delivery defect of the site, and the run is not PASSED.
            f.checks("visible_text").map { it.verdict }.toSet() shouldBe setOf(Verdict.INCONCLUSIVE)
            f.checks("visible_text").forEach { it.note!! shouldStartWith "stale_text: the receiver's page showed \"$text\" already" }
            f.checks("latency_max").map { it.verdict to it.note }.toSet() shouldBe
                setOf(Verdict.INCONCLUSIVE to "no latency measured: the preceding visible_text proves no delivery")
            f.evidence.receiptList.map { it.received } shouldContainExactly listOf(false, false)
            summary.assertionsFailed shouldBe 0
            summary.assertionsInconclusive shouldBe 4
            summary.outcome shouldBe RunOutcome.FAILED
        }

    @Test
    fun `in the account swap the text the first pass published proves no delivery`() =
        runTest {
            val f = fixture()
            // The site keeps what was announced: every page opened later shows it from the start.
            val published = CopyOnWriteArraySet<String>()
            f.browser.configure = { session -> published.forEach(session::showText) }
            f.agents.script = { call, _ ->
                if (call.scenarioStep.startsWith("announce")) {
                    f.browser.sessions.values
                        .forEach { it.showText(text) }
                    published += text
                }
                ok
            }

            f.runner().run(announcing(listOf(AssertionSpec.VisibleText(text, 5.seconds))), RunOptions(swapAccounts = true))

            val visible = f.checks("visible_text").groupBy({ it.scenarioStep }, { it.verdict })
            visible["read"] shouldBe listOf(Verdict.PASSED, Verdict.PASSED)
            visible["read@swap"] shouldBe listOf(Verdict.INCONCLUSIVE, Verdict.INCONCLUSIVE)
            f.checks("visible_text").filter { it.scenarioStep == "read@swap" }.forEach { it.note!! shouldStartWith "stale_text" }
        }

    @Test
    fun `a text marked with its pass is new in the account swap, so the swap's delivery is measured too`() =
        runTest {
            val f = fixture()
            val published = CopyOnWriteArraySet<String>()
            f.browser.configure = { session -> published.forEach(session::showText) }
            f.agents.script = { call, _ ->
                if (call.scenarioStep.startsWith("announce")) {
                    // The agent writes what its task says, with the pass's mark in it.
                    val written = (call.action as StepAction.Do).instruction.substringAfter("'").substringBefore("'")
                    f.browser.sessions.values
                        .forEach { it.showText(written) }
                    published += written
                }
                ok
            }
            val marked = "$text {pass}"
            val campaign =
                campaign(
                    managers = 0,
                    employees = 2,
                    steps =
                        listOf(
                            step("announce", admin(), StepAction.Do("Announce '$marked'"), emits = "announcement_created"),
                            step(
                                "read",
                                employees(),
                                waitFor = "announcement_created",
                                assertions = listOf(AssertionSpec.VisibleText(marked, 5.seconds)),
                            ),
                        ),
                )

            f.runner().run(campaign, RunOptions(swapAccounts = true))

            val tag =
                f.evidence.runList
                    .single()
                    .runTag.value
            val visible = f.checks("visible_text").groupBy({ it.scenarioStep }, { it.verdict to it.expected.substringBefore(" visible") })
            visible["read"] shouldBe List(2) { Verdict.PASSED to "\"$text $tag-1\"" }
            visible["read@swap"] shouldBe List(2) { Verdict.PASSED to "\"$text $tag-2\"" }
            f.evidence.receiptList.map { it.received } shouldContainExactly List(4) { true }
        }

    @Test
    fun `a text that names the event's object is checked after the event, as an upper bound`() =
        runTest {
            val f = fixture()
            f.announce(deliveredAfter = 300.milliseconds, answersAfter = 1.seconds)
            val named = "$text {last_id}"
            f.agents.script.let { announce ->
                f.agents.script = { call, runtime ->
                    val outcome = announce(call, runtime)
                    if (call.scenarioStep == "announce") readers.forEach { f.browser.session(it).showText("$text n1") }
                    outcome
                }
            }

            f.runner().run(announcing(listOf(AssertionSpec.VisibleText(named, 5.seconds))))

            readers.forEach {
                f.browser
                    .session(it)
                    .watched
                    .shouldBeEmpty()
            }
            f.checks("visible_text").map { it.verdict to it.note }.toSet() shouldBe
                setOf(Verdict.PASSED to "latency is an upper bound: the text was visible already when first checked")
            f.evidence.receiptList.map { it.latencyMs } shouldContainExactly listOf(1_300L, 1_300L)
        }

    @Test
    fun `without a write the delay is bounded by the start of the action and the limit is not confirmed`() =
        runTest {
            val f = fixture()
            f.announce(deliveredAfter = 300.milliseconds, answersAfter = 2.seconds, writes = emptyList())

            f.runner().run(announcing())

            f.step("announce", StepKind.EMIT, "a01").detail!! shouldContain
                "the page sent no accepted request to the site; latency is measured from this publish"
            // Seen 2 s before the publish: counted as 0, but it may have taken up to 300 ms after the action began.
            f.evidence.receiptList.map { it.latencyMs } shouldContainExactly listOf(0L, 0L)
            f.checks("latency_max").map { it.verdict }.toSet() shouldBe setOf(Verdict.PASSED)
        }

    @Test
    fun `every watch ends by the end of the run, also for readers that never read`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                when {
                    call.scenarioStep == "join" && call.agentId == AgentId("a03") -> {
                        ActionOutcome(ActionStatus.FAILED, "could not join", failureReason = FailureReason.PROBLEM_REPORTED)
                    }

                    call.scenarioStep == "prepare" -> {
                        readers.forEach { f.browser.session(it).showText(text) }
                        ok
                    }

                    else -> {
                        ok
                    }
                }
            }
            val prepared =
                campaign(
                    managers = 0,
                    employees = 2,
                    setup =
                        listOf(
                            step("prepare", admin(), emits = "ready", phase = StepPhase.SETUP),
                            setupStep("join", employees()),
                        ),
                    steps =
                        listOf(
                            step("read", employees(), waitFor = "ready", assertions = listOf(AssertionSpec.VisibleText(text, 5.seconds))),
                        ),
                )

            f.runner().run(prepared)

            // Both readers watched from the start of 'prepare'; a03 failed to join and never read, yet its watch ended too.
            readers.forEach { f.browser.session(it).watched shouldContainExactly listOf("read" to text) }
            f.checks("visible_text").map { it.agentId?.value to it.verdict } shouldContainExactly listOf("a02" to Verdict.PASSED)
            f.browser.sessions.values
                .forEach { it.activeWatches.shouldBeEmpty() }
        }

    @Test
    fun `readers of an event that never came end their watches`() =
        runTest {
            val f = fixture()
            f.agents.script = { call, _ ->
                if (call.scenarioStep == "announce") ActionOutcome(ActionStatus.FAILED, "the form was refused") else ok
            }

            f.runner().run(
                campaign(
                    managers = 0,
                    employees = 2,
                    steps =
                        listOf(
                            step("announce", admin(), emits = "announcement_created"),
                            step(
                                "read",
                                employees(),
                                waitFor = "announcement_created",
                                waitTimeout = 2.seconds,
                                assertions = listOf(AssertionSpec.VisibleText(text, 5.seconds)),
                            ),
                        ),
                ),
            )

            readers.forEach { f.browser.session(it).watched shouldContainExactly listOf("read" to text) }
            f.browser.sessions.values
                .forEach { it.activeWatches.shouldBeEmpty() }
        }
}
