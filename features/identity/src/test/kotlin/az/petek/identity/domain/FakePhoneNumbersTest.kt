package az.petek.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.util.BitSet
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class FakePhoneNumbersTest {
    @Test
    fun `numbers are distinct fake Azerbaijani mobiles outside the allocated subscriber ranges`() {
        val phones = FakePhoneNumbers.sample(10_000, Random(1))

        phones shouldHaveSize 10_000
        phones.shouldNotContainDuplicates()
        phones.forEach { it shouldMatch Regex("\\+99450[01]\\d{6}") }
    }

    @Test
    fun `the same random source gives the same numbers`() {
        FakePhoneNumbers.sample(50, Random(7)) shouldBe FakePhoneNumbers.sample(50, Random(7))
        FakePhoneNumbers.sample(50, Random(7)) shouldNotBe FakePhoneNumbers.sample(50, Random(8))
    }

    @Test
    fun `even the whole range is drawn in linear time without retries`() {
        val (phones, took) = measureTimedValue { FakePhoneNumbers.sample(FakePhoneNumbers.CAPACITY, Random(3)) }

        took shouldBeLessThan 10.seconds
        phones shouldHaveSize FakePhoneNumbers.CAPACITY
        val subscribers = BitSet(FakePhoneNumbers.CAPACITY)
        phones.forEach { subscribers.set(it.removePrefix("+99450").toInt()) }
        subscribers.cardinality() shouldBe FakePhoneNumbers.CAPACITY
    }

    @Test
    fun `no numbers are drawn for no testers and more than the range cannot be drawn`() {
        FakePhoneNumbers.sample(0, Random(1)).shouldBeEmpty()
        shouldThrow<IllegalArgumentException> { FakePhoneNumbers.sample(FakePhoneNumbers.CAPACITY + 1, Random(1)) }
        shouldThrow<IllegalArgumentException> { FakePhoneNumbers.sample(-1, Random(1)) }
    }
}
