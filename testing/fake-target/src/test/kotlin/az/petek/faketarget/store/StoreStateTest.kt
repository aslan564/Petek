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
