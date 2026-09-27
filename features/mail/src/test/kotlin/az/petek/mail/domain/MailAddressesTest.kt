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

package az.petek.mail.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MailAddressesTest {
    @Test
    fun `a plus address is built from the owner's box and a tag, lower case`() {
        MailAddresses.plus("Test@Company.EXAMPLE", "r1ab-a01") shouldBe "test+r1ab-a01@company.example"
        MailAddresses.plus("test+old@company.example", "a02") shouldBe "test+a02@company.example"
    }

    @Test
    fun `addresses compare whole, so a plus address never matches the box or another tester`() {
        MailAddresses.same(" TEST+a01@company.example", "test+a01@company.example") shouldBe true
        MailAddresses.same("test+a01@company.example", "test@company.example") shouldBe false
        MailAddresses.same("test+a01@company.example", "test+a02@company.example") shouldBe false
    }

    @Test
    fun `only a single bare address and a plain tag are accepted`() {
        shouldThrow<IllegalArgumentException> { MailAddresses.normalize("Test <test@company.example>") }
        shouldThrow<IllegalArgumentException> { MailAddresses.plus("test@company.example", "a b") }
    }
}
