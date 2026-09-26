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

import az.petek.browser.domain.ObservedMutation
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.RequestPattern
import az.petek.core.ids.AgentId
import az.petek.core.testing.FakeHarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.verification.testing.FakeTemplateRenderer
import az.petek.verification.testing.SimpleJsonFieldSelector
import az.petek.verification.testing.assertionInput
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class OnlyOneSucceedsTest {
    private val oracle = FakeTargetOracle()

    private fun evaluator(target: TargetOracle = oracle) =
        DefaultAssertionEvaluator(target, FakeTemplateRenderer(), SimpleJsonFieldSelector(), FakeHarnessClock())

    private val evaluator = evaluator()

    private fun actor(
        index: Int,
        succeeded: Boolean,
    ) = ActorResult(AgentId.of(index), succeeded, if (succeeded) "approved" else "409 already decided")

    private fun request(
        status: Int,
        path: String = "/tickets/t2/approve",
        method: String = "POST",
    ) = ObservedMutation(method, path, status, HarnessTimestamp(Instant.parse("2026-01-01T10:00:00Z"), 0))

    /** An actor whose result is derived from its [requests] the way the orchestrator does it. */
    private fun racer(
        index: Int,
        vararg requests: ObservedMutation,
        summary: String = "done",
        lostRace: Boolean = false,
    ): ActorResult {
        val race = RaceEvidence.of(APPROVE, requests.toList())
        return ActorResult(AgentId.of(index), race.succeeded, summary, race, lostRace)
    }

    private val input = assertionInput(session = null, agentId = null, scenarioStep = "race")

    // --- verdict from code-derived results ----------------------------------------------------------------------------

    @Test
    fun `exactly one winner passes and names it`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(2, false), actor(3, true)))

        result.verdict shouldBe Verdict.PASSED
        result.source shouldBe EvidenceSource.SENDER
        result.spec shouldBe AssertionSpec.OnlyOneSucceeds()
        result.expected shouldBe "exactly one of 2 actors succeeds"
        result.observed shouldBe "a02 did not succeed; a03 succeeded"
        result.note.shouldBeNull()
        result.latency.shouldBeNull()
        result.oracleEvidence.shouldBeNull()
    }

    @Test
    fun `no winner fails`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(2, false), actor(3, false)))

        result.verdict shouldBe Verdict.FAILED
        result.note shouldBe "no actor succeeded; expected exactly one winner"
    }

    @Test
    fun `two winners fail and name who succeeded in agent order`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(10, true), actor(2, true), actor(3, false)))

        result.verdict shouldBe Verdict.FAILED
        result.observed shouldBe "a02 succeeded; a03 did not succeed; a10 succeeded"
        result.note shouldBe "more than one actor succeeded (a02, a10); expected exactly one"
    }

    @Test
    fun `no actor results fail instead of passing vacuously`() {
        val result = evaluator.evaluateOnlyOneSucceeds(emptyList())

        result.verdict shouldBe Verdict.FAILED
        result.observed shouldBe "no actors"
        result.note shouldBe "no actor results to compare"
    }

    @Test
    fun `the observed text lists the decisive request of every actor`() =
        runTest {
            val results = listOf(racer(3, request(409)), racer(2, request(303)), racer(4))

            val result = evaluator.evaluateOnlyOneSucceeds(AssertionSpec.OnlyOneSucceeds(APPROVE), results, input)

            result.verdict shouldBe Verdict.PASSED
            result.expected shouldBe "exactly one of 3 actors succeeds by `POST .*/approve`"
            result.observed shouldBe
                "a02 POST /tickets/t2/approve -> 303; a03 POST /tickets/t2/approve -> 409; a04 no matching request"
        }

    @Test
    fun `the agent's own claim never decides, only the requests do`() =
        runTest {
            // Both agents said "done, success": the target accepted only a02's approval.
            val results =
                listOf(
                    racer(2, request(303), summary = "Ticket approved"),
                    racer(3, request(409), summary = "Ticket approved successfully"),
                )

            val result = evaluator.evaluateOnlyOneSucceeds(AssertionSpec.OnlyOneSucceeds(APPROVE), results, input)

            result.verdict shouldBe Verdict.PASSED
            result.observed shouldBe "a02 POST /tickets/t2/approve -> 303; a03 POST /tickets/t2/approve -> 409"
        }

    @Test
    fun `one winner is not enough when another actor's requests could not be read`() =
        runTest {
            val unknown = RaceEvidence.unavailable("BrowserActionException: browser session 'a03' is closed")
            val results = listOf(racer(2, request(303)), ActorResult(AgentId.of(3), unknown.succeeded, "done", unknown))

            val result = evaluator.evaluateOnlyOneSucceeds(AssertionSpec.OnlyOneSucceeds(APPROVE), results, input)

            result.verdict shouldBe Verdict.FAILED
            result.note shouldBe "the requests of a03 could not be read"
            result.observed shouldBe
                "a02 POST /tickets/t2/approve -> 303; a03 requests unavailable: BrowserActionException: browser session 'a03' is closed"
        }

    @Test
    fun `the raw evidence lists every actor's requests and summary as JSON`() =
        runTest {
            val results = listOf(racer(3, request(409), summary = "already decided", lostRace = true), racer(2, request(303)))

            val result = evaluator.evaluateOnlyOneSucceeds(AssertionSpec.OnlyOneSucceeds(APPROVE), results, input)

            val json = Json.parseToJsonElement(result.rawEvidence!!).jsonObject
            json["assertion"]!!.jsonPrimitive.content shouldBe "only_one_succeeds"
            json["request"]!!.jsonPrimitive.content shouldBe "POST .*/approve"
            json["winners"]!!.jsonPrimitive.int shouldBe 1
            json["winner_ids"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("a02")
            val actors = json["actors"]!!.jsonArray.map { it.jsonObject }
            actors.map { it["agent_id"]!!.jsonPrimitive.content } shouldBe listOf("a02", "a03")
            actors[1]["summary"]!!.jsonPrimitive.content shouldBe "already decided"
            actors[1]["lost_race"]!!.jsonPrimitive.boolean shouldBe true
            actors[1]["decisive"]!!.jsonPrimitive.content shouldBe "POST /tickets/t2/approve -> 409"
            val sent = actors[1]["requests"]!!.jsonArray.single().jsonObject
            sent["status"]!!.jsonPrimitive.int shouldBe 409
            sent["at"]!!.jsonPrimitive.content shouldBe "2026-01-01T10:00:00Z"
        }

    @Test
    fun `without a request pattern the evidence says any mutating request decides`() {
        val json = Json.parseToJsonElement(evaluator.evaluateOnlyOneSucceeds(listOf(actor(2, true))).rawEvidence!!).jsonObject

        json["request"]!!.jsonPrimitive.content shouldBe "* .*"
    }

    // --- oracle condition ------------------------------------------------------------------------------------------------

    private val approvedTicket = """{"id": "42", "status": "approved"}"""

    private fun withOracle(equals: String = "approved") =
        AssertionSpec.OnlyOneSucceeds(APPROVE, OracleCondition("/test/tickets/{last_id}", "status", equals))

    @Test
    fun `an oracle condition that holds keeps the race verdict and its body as evidence`() =
        runTest {
            oracle.respond("/test/tickets/42", approvedTicket)

            val result = evaluator.evaluateOnlyOneSucceeds(withOracle(), listOf(racer(2, request(303)), racer(3, request(409))), input)

            result.verdict shouldBe Verdict.PASSED
            result.expected shouldBe
                "exactly one of 2 actors succeeds by `POST .*/approve` and GET /test/tickets/42 field `status` = \"approved\""
            result.observed shouldBe "a02 POST /tickets/t2/approve -> 303; a03 POST /tickets/t2/approve -> 409; oracle: status = approved"
            result.oracleEvidence shouldBe approvedTicket
            result.note.shouldBeNull()
            Json
                .parseToJsonElement(result.rawEvidence!!)
                .jsonObject["oracle"]!!
                .jsonObject["verdict"]!!
                .jsonPrimitive.content shouldBe "PASSED"
        }

    @Test
    fun `an oracle condition that does not hold fails a race with one winner`() =
        runTest {
            oracle.respond("/test/tickets/42", approvedTicket)

            val result =
                evaluator.evaluateOnlyOneSucceeds(withOracle("rejected"), listOf(racer(2, request(303)), racer(3, request(409))), input)

            result.verdict shouldBe Verdict.FAILED
            result.note shouldBe "oracle: expected \"rejected\" but was \"approved\""
            result.oracleEvidence shouldBe approvedTicket
        }

    @Test
    fun `a holding oracle condition cannot rescue a race without exactly one winner`() =
        runTest {
            oracle.respond("/test/tickets/42", approvedTicket)

            val result = evaluator.evaluateOnlyOneSucceeds(withOracle(), listOf(racer(2, request(303)), racer(3, request(200))), input)

            result.verdict shouldBe Verdict.FAILED
            result.note shouldBe "more than one actor succeeded (a02, a03); expected exactly one"
        }

    @Test
    fun `without a test API the oracle condition is skipped with a note and the requests decide`() =
        runTest {
            val result =
                evaluator(FakeTargetOracle(isAvailable = false))
                    .evaluateOnlyOneSucceeds(withOracle(), listOf(racer(2, request(303)), racer(3, request(409))), input)

            result.verdict shouldBe Verdict.PASSED
            result.note shouldBe "oracle: N/A (no oracle)"
            result.observed!! shouldContain "; oracle: N/A (no oracle)"
            result.oracleEvidence.shouldBeNull()
        }

    @Test
    fun `an oracle that fails or cannot be rendered fails the race with a note`() =
        runTest {
            val broken =
                object : TargetOracle by FakeTargetOracle() {
                    override suspend fun get(path: String): OracleResponse = throw OracleException("connection refused")
                }
            val winners = listOf(racer(2, request(303)), racer(3, request(409)))

            val failing = evaluator(broken).evaluateOnlyOneSucceeds(withOracle(), winners, input)
            val unrendered =
                evaluator.evaluateOnlyOneSucceeds(
                    AssertionSpec.OnlyOneSucceeds(oracle = OracleCondition("/test/tickets/{event.missing.id}")),
                    winners,
                    input,
                )

            failing.verdict shouldBe Verdict.FAILED
            failing.note shouldBe "oracle: check failed: OracleException: connection refused"
            unrendered.verdict shouldBe Verdict.FAILED
            unrendered.note!! shouldContain "oracle: template error"
        }

    @Test
    fun `an oracle path that leaves the target is refused without a request`() =
        runTest {
            val traversal = AssertionSpec.OnlyOneSucceeds(oracle = OracleCondition("/test/{last_id}"))
            val sneaky = assertionInput(session = null, agentId = null, templates = input.templates.copy(lastId = ".."))

            val result = evaluator.evaluateOnlyOneSucceeds(traversal, listOf(racer(2, request(303))), sneaky)

            result.verdict shouldBe Verdict.FAILED
            result.note!! shouldContain "oracle: refused to request"
        }

    // --- scope -----------------------------------------------------------------------------------------------------------

    @Test
    fun `only only_one_succeeds is a group-level assertion`() {
        AssertionSpec.OnlyOneSucceeds().isGroupLevel shouldBe true
        AssertionSpec.OnlyOneSucceeds(APPROVE, OracleCondition("/x")).isGroupLevel shouldBe true
        listOf(
            AssertionSpec.VisibleText("x", 1.seconds),
            AssertionSpec.NotVisible("x", null),
            AssertionSpec.Oracle("/p", null, null, null),
            AssertionSpec.HttpStatus("/p", "GET", 200),
            AssertionSpec.Count("#x", 1),
            AssertionSpec.LatencyMax(1.seconds),
        ).map { it.isGroupLevel } shouldBe List(6) { false }
    }

    private companion object {
        val APPROVE = RequestPattern("POST", ".*/approve")
    }
}
