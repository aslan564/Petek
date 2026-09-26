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

package az.petek.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.util.BitSet
import kotlin.random.Random

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
    fun `even the whole range is drawn with exactly one random draw per number`() {
        val random = CountingRandom(Random(3))

        val phones = FakePhoneNumbers.sample(FakePhoneNumbers.CAPACITY, random)

        random.draws shouldBe FakePhoneNumbers.CAPACITY.toLong()
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

    /** Counts the bounded draws, so "no retries" is checked by counting instead of by timing. */
    private class CountingRandom(
        private val delegate: Random,
    ) : Random() {
        var draws = 0L

        override fun nextBits(bitCount: Int): Int = delegate.nextBits(bitCount)

        override fun nextInt(until: Int): Int {
            draws++
            return delegate.nextInt(until)
        }
    }
}
