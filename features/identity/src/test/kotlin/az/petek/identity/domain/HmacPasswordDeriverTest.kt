package az.petek.identity.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTag
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class HmacPasswordDeriverTest {
    private val runTag = RunTag("k7x2")
    private val deriver = HmacPasswordDeriver("local-secret".toByteArray())
    private val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + HmacPasswordDeriver.SYMBOLS.toList()

    private fun passwords(
        deriver: HmacPasswordDeriver = this.deriver,
        tags: List<RunTag> = listOf(runTag, RunTag("0000"), RunTag("zzzz")),
    ): List<String> = tags.flatMap { tag -> (1..300).map { deriver.derive(tag, AgentId.of(it)).reveal() } }

    @Test
    fun `every password has 16 characters with upper, lower, digit and exactly one symbol`() {
        passwords().forEach { password ->
            password.length shouldBe HmacPasswordDeriver.LENGTH
            password.any { it.isUpperCase() } shouldBe true
            password.any { it.isLowerCase() } shouldBe true
            password.any { it.isDigit() } shouldBe true
            password.count { it in HmacPasswordDeriver.SYMBOLS } shouldBe 1
            password.all { it in allowed } shouldBe true
        }
    }

    @Test
    fun `the symbol satisfies every common special-character rule`() {
        val rules = listOf(Regex("\\W"), Regex("[!@#$%^&*]"), Regex("[^A-Za-z0-9]"), Regex("\\p{Punct}"))
        passwords().forEach { password ->
            rules.forEach { rule -> rule.containsMatchIn(password) shouldBe true }
            password.count { it in "!@#%*-_" } shouldBe 1
        }
    }

    @Test
    fun `passwords start with a letter and leave out look-alike characters`() {
        passwords().forEach { password ->
            password.first().isUpperCase() shouldBe true
            password.none { it in "IOl01" } shouldBe true
        }
    }

    @Test
    fun `the symbol is not always in the same position`() {
        passwords().map { password -> password.indexOfFirst { it in HmacPasswordDeriver.SYMBOLS } }.toSet().size shouldBe 15
    }

    @Test
    fun `the same secret, run tag and agent always give the same password`() {
        val again = HmacPasswordDeriver("local-secret".toByteArray())
        passwords(again) shouldBe passwords()
    }

    @Test
    fun `the algorithm is stable so stored passwords can be re-derived after an upgrade`() {
        deriver.derive(runTag, AgentId("a07")).reveal() shouldBe KNOWN_A07
    }

    @Test
    fun `every agent and run tag gets its own password`() {
        passwords().shouldNotContainDuplicates()
    }

    @Test
    fun `another secret gives other passwords`() {
        val other = HmacPasswordDeriver("local-secreT".toByteArray())
        passwords(other).zip(passwords()).forEach { (a, b) -> a shouldNotBe b }
    }

    @Test
    fun `an empty secret is rejected`() {
        val error = shouldThrow<IllegalArgumentException> { HmacPasswordDeriver(ByteArray(0)) }
        error.message shouldContain "secret"
    }

    @Test
    fun `changing the caller's secret array later has no effect`() {
        val secret = "local-secret".toByteArray()
        val deriver = HmacPasswordDeriver(secret)
        secret.fill(0)
        deriver.derive(runTag, AgentId("a07")).reveal() shouldBe KNOWN_A07
    }

    @Test
    fun `the derived password never shows up in text output`() {
        val password = deriver.derive(runTag, AgentId("a07"))
        password.toString() shouldBe "Secret(***)"
    }

    private companion object {
        const val KNOWN_A07 = "KLb5!NiUN53Nf8BX"
    }
}
