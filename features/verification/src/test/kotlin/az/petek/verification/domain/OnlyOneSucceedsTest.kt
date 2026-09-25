package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec
import az.petek.core.ids.AgentId
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.verification.testing.FakeTemplateRenderer
import az.petek.verification.testing.SimpleJsonFieldSelector
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class OnlyOneSucceedsTest {
    private val evaluator =
        DefaultAssertionEvaluator(FakeTargetOracle(), FakeTemplateRenderer(), SimpleJsonFieldSelector(), FakeHarnessClock())

    private fun actor(
        index: Int,
        succeeded: Boolean,
    ) = ActorResult(AgentId.of(index), succeeded, if (succeeded) "approved" else "409 already decided")

    @Test
    fun `exactly one winner passes and names it`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(2, false), actor(3, true)))

        result.verdict shouldBe Verdict.PASSED
        result.source shouldBe EvidenceSource.SENDER
        result.spec shouldBe AssertionSpec.OnlyOneSucceeds
        result.expected shouldBe "exactly one of 2 actors succeeds"
        result.observed shouldBe "a03 succeeded"
        result.note.shouldBeNull()
        result.latency.shouldBeNull()
    }

    @Test
    fun `no winner fails`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(2, false), actor(3, false)))

        result.verdict shouldBe Verdict.FAILED
        result.observed shouldBe "none of 2 succeeded"
        result.note shouldBe "no actor succeeded; expected exactly one winner"
    }

    @Test
    fun `two winners fail and name who succeeded in agent order`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(10, true), actor(2, true), actor(3, false)))

        result.verdict shouldBe Verdict.FAILED
        result.observed shouldBe "2 succeeded: a02, a10"
        result.note shouldBe "more than one actor succeeded (a02, a10); expected exactly one"
    }

    @Test
    fun `no actor results fail instead of passing vacuously`() {
        val result = evaluator.evaluateOnlyOneSucceeds(emptyList())

        result.verdict shouldBe Verdict.FAILED
        result.observed shouldBe "none of 0 succeeded"
        result.note shouldBe "no actor results to compare"
    }

    @Test
    fun `the raw evidence lists every actor outcome as JSON`() {
        val result = evaluator.evaluateOnlyOneSucceeds(listOf(actor(3, false), actor(2, true)))

        val json = Json.parseToJsonElement(result.rawEvidence!!).jsonObject
        json["assertion"]!!.jsonPrimitive.content shouldBe "only_one_succeeds"
        json["winners"]!!.jsonPrimitive.int shouldBe 1
        json["winner_ids"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("a02")
        val actors = json["actors"]!!.jsonArray.map { it.jsonObject }
        actors.map { it["agent_id"]!!.jsonPrimitive.content } shouldBe listOf("a02", "a03")
        actors[1]["summary"]!!.jsonPrimitive.content shouldBe "409 already decided"
    }

    @Test
    fun `only only_one_succeeds is a group-level assertion`() {
        AssertionSpec.OnlyOneSucceeds.isGroupLevel shouldBe true
        listOf(
            AssertionSpec.VisibleText("x", 1.seconds),
            AssertionSpec.NotVisible("x", null),
            AssertionSpec.Oracle("/p", null, null, null),
            AssertionSpec.HttpStatus("/p", "GET", 200),
            AssertionSpec.Count("#x", 1),
            AssertionSpec.LatencyMax(1.seconds),
        ).map { it.isGroupLevel } shouldBe List(6) { false }
    }
}
