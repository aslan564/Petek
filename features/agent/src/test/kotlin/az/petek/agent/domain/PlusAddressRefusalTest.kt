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

package az.petek.agent.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlusAddressRefusalTest {
    @Test
    fun `a plus address with an e-mail error on the page is recognised, in several languages`() {
        PlusAddressRefusal.detect("test+r1-a01@company.az", "Please enter a valid email address") shouldBe true
        PlusAddressRefusal.detect("test+r1-a01@company.az", "E-poçt ünvanı düzgün deyil") shouldBe true
        PlusAddressRefusal.detect("test+r1-a01@company.az", "Некорректный адрес почты") shouldBe true
    }

    @Test
    fun `no plus address, or no e-mail error, is not a plus refusal`() {
        PlusAddressRefusal.detect("anar.r1.a01@test.portal.example", "Please enter a valid email address") shouldBe false
        PlusAddressRefusal.detect("test+r1-a01@company.az", "Password is too short") shouldBe false
    }
}
