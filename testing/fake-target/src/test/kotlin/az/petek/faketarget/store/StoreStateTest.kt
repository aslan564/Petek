/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.store

import az.petek.faketarget.model.Company
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class StoreStateTest {
    private val state = StoreState()

    private fun company(code: String) {
        val id = state.nextId("c")
        state.companies[id] = Company(id, "Firma", code, "$id@test.kadrohr.com", isTest = true, createdAt = Instant.EPOCH)
    }

    @Test
    fun `a company code is the first candidate nobody uses, compared case-insensitively`() {
        company("PTK-1000")
        state.unusedCompanyCode(sequenceOf("ptk-1000", "PTK-1000", "PTK-2000")) shouldBe "PTK-2000"
    }

    @Test
    fun `when every four-digit code is taken the longer fallback still yields a code`() {
        (1_000..9_999).forEach { company("PTK-$it") }
        val fourDigits = generateSequence(1_000) { it + 1 }.take(50).map { "PTK-$it" }
        state.unusedCompanyCode(fourDigits + sequenceOf("PTK-123456")) shouldBe "PTK-123456"
    }
}
