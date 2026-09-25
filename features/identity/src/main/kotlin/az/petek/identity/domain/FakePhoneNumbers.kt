/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
