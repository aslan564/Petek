package az.petek.core.ids

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class AgentIdTest {
    @Test
    fun `ids are zero-padded to two digits and grow without a limit`() {
        AgentId.of(1).value shouldBe "a01"
        AgentId.of(7).value shouldBe "a07"
        AgentId.of(99).value shouldBe "a99"
        AgentId.of(100).value shouldBe "a100"
        AgentId.of(999).value shouldBe "a999"
        AgentId.of(1000).value shouldBe "a1000"
        AgentId.of(123_456).value shouldBe "a123456"
    }

    @Test
    fun `the index is the number after the letter`() {
        AgentId("a01").index shouldBe 1
        AgentId("a99").index shouldBe 99
        AgentId("a100").index shouldBe 100
        AgentId("a5000").index shouldBe 5000
        AgentId.of(Int.MAX_VALUE).index shouldBe Int.MAX_VALUE
    }

    @Test
    fun `ids order by their number, so a99 comes before a100`() {
        AgentId("a99") shouldBeLessThan AgentId("a100")
        AgentId("a100") shouldBeLessThan AgentId("a1000")
        AgentId("a09") shouldBeLessThan AgentId("a10")
        val shuffled = listOf("a100", "a02", "a1000", "a99", "a10", "a01").map(::AgentId)
        shuffled.sorted().map { it.value } shouldContainExactly listOf("a01", "a02", "a10", "a99", "a100", "a1000")
    }

    @Test
    fun `equal ids compare as equal`() {
        AgentId("a100").compareTo(AgentId.of(100)) shouldBe 0
        AgentId("a100") shouldBe AgentId.of(100)
    }

    @Test
    fun `only the canonical spelling is accepted`() {
        listOf("a7", "a007", "a0100", "a00", "a0", "a", "b01", "A01", "a-1", "a1x", " a01", "a01 ", "", "a99999999999")
            .forEach { raw ->
                shouldThrow<IllegalArgumentException> { AgentId(raw) }.message shouldContain "was '$raw'"
            }
    }

    @Test
    fun `an index below one is rejected`() {
        shouldThrow<IllegalArgumentException> { AgentId.of(0) }.message shouldBe "Agent index must be positive, was 0"
        shouldThrow<IllegalArgumentException> { AgentId.of(-3) }
    }

    @Test
    fun `parseOrNull accepts exactly what the constructor accepts`() {
        AgentId.parseOrNull("a1000") shouldBe AgentId("a1000")
        AgentId.parseOrNull("a07") shouldBe AgentId("a07")
        AgentId.parseOrNull("a007") shouldBe null
        AgentId.parseOrNull("a00") shouldBe null
        AgentId.parseOrNull("screenshots") shouldBe null
        AgentId.parseOrNull("a99999999999") shouldBe null
    }
}
