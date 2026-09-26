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

package az.petek.app.panel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContactsTest {
    @Test
    fun `e-mail addresses keep only their domain and everything else stays`() {
        Contacts.masked("a01: emit -> FAILED: oracle: GET /test/tickets/latest?by=tural.18mc.a01@test.portal.example answered 404") shouldBe
            "a01: emit -> FAILED: oracle: GET /test/tickets/latest?by=***@test.portal.example answered 404"
        Contacts.masked("eli+qa@portal.test, vəli@x.az və mətn") shouldBe "***@portal.test, ***@x.az və mətn"
        Contacts.masked("heç bir ünvan yoxdur @ burada") shouldBe "heç bir ünvan yoxdur @ burada"
    }
}
