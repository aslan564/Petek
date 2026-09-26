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

import kotlin.random.Random

/**
 * Fake Azerbaijani mobile numbers `+99450` + 7 digits with subscriber numbers 0000000..1999999. Azerbaijani mobile
 * subscriber numbers start with 2..9, so these are outside the allocated ranges and can never reach a real person,
 * even if an SMS were sent by mistake. The range holds [CAPACITY] numbers, one per tester.
 */
internal object FakePhoneNumbers {
    const val CAPACITY = 2_000_000

    private const val PREFIX = "+99450"
    private const val SUBSCRIBER_DIGITS = 7

    /**
     * [count] distinct numbers chosen uniformly at random with Floyd's sampling algorithm: exactly one random draw per
     * number and no retries, so the cost stays linear however close [count] comes to [CAPACITY].
     */
    fun sample(
        count: Int,
        random: Random,
    ): List<String> {
        require(count in 0..CAPACITY) { "between 0 and $CAPACITY phone numbers can be generated, was $count" }
        val chosen = LinkedHashSet<Int>(count * 2)
        for (bound in CAPACITY - count until CAPACITY) {
            val candidate = random.nextInt(bound + 1)
            chosen += if (candidate in chosen) bound else candidate
        }
        return chosen.map { PREFIX + it.toString().padStart(SUBSCRIBER_DIGITS, '0') }
    }
}
