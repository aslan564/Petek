package az.petek.verification.domain

import az.petek.browser.domain.ObservedMutation
import az.petek.campaign.domain.RequestPattern
import az.petek.core.time.HarnessTimestamp
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class RaceEvidenceTest {
    private var clock = 0L

    private fun sent(
        status: Int,
        path: String = "/tickets/t2/approve",
        method: String = "POST",
    ) = ObservedMutation(method, path, status, HarnessTimestamp(Instant.EPOCH.plusNanos(++clock), clock))

    private fun evidence(vararg requests: ObservedMutation) = RaceEvidence.of(APPROVE, requests.toList())

    @Test
    fun `an accepted request wins, a form post's redirect included`() {
        listOf(200, 201, 204, 302, 303).forEach { status ->
            val race = evidence(sent(status))
            race.succeeded shouldBe true
            race.decisive?.status shouldBe status
            race.refusedAsDecided shouldBe false
        }
    }

    @Test
    fun `a conflict loses the race because the object was already decided`() {
        listOf(409, 422).forEach { status ->
            val race = evidence(sent(status))
            race.succeeded shouldBe false
            race.refusedAsDecided shouldBe true
            race.describe() shouldBe "POST /tickets/t2/approve -> $status"
        }
    }

    @Test
    fun `a forbidden request is a refusal but not a lost race`() {
        val race = evidence(sent(403))

        race.succeeded shouldBe false
        race.refusedAsDecided shouldBe false
        race.decisive?.status shouldBe 403
    }

    @Test
    fun `a refusal outweighs an unrelated accepted request`() {
        val race = RaceEvidence.of(RequestPattern.ANY_MUTATION, listOf(sent(200, "/api/notifications/read"), sent(409)))

        race.succeeded shouldBe false
        race.refusedAsDecided shouldBe true
        race.decisive?.path shouldBe "/tickets/t2/approve"
    }

    @Test
    fun `a double submit of a request already won stays a win`() {
        val race = evidence(sent(303), sent(409))

        race.succeeded shouldBe true
        race.decisive?.status shouldBe 303
    }

    @Test
    fun `a refusal before the actor's own success still counts`() {
        val race = evidence(sent(409), sent(303))

        race.succeeded shouldBe false
        race.refusedAsDecided shouldBe true
        race.decisive?.status shouldBe 409
    }

    @Test
    fun `server errors and client errors other than refusals neither win nor lose`() {
        val race = evidence(sent(500), sent(400))

        race.succeeded shouldBe false
        race.refusedAsDecided shouldBe false
        race.decisive?.status shouldBe 400
    }

    @Test
    fun `a retry that the target accepts after a server error wins`() {
        val race = evidence(sent(502), sent(303))

        race.succeeded shouldBe true
        race.decisive?.status shouldBe 303
    }

    @Test
    fun `only requests matching the pattern count`() {
        val race = evidence(sent(200, "/tickets/t2/comment"), sent(200, "/tickets/t2/approve", "PUT"), sent(409))

        race.requests.map { it.describe() } shouldContainExactly listOf("POST /tickets/t2/approve -> 409")
        race.succeeded shouldBe false
    }

    @Test
    fun `no matching request is no win and says so`() {
        val race = evidence(sent(200, "/tickets/t2/comment"))

        race.succeeded shouldBe false
        race.refusedAsDecided shouldBe false
        race.decisive.shouldBeNull()
        race.describe() shouldBe "no matching request"
    }

    @Test
    fun `requests that could not be read are no win and say why`() {
        val race = RaceEvidence.unavailable("browser session 'a03' is closed")

        race.succeeded shouldBe false
        race.describe() shouldBe "requests unavailable: browser session 'a03' is closed"
    }

    private companion object {
        val APPROVE = RequestPattern("POST", ".*/approve")
    }
}
