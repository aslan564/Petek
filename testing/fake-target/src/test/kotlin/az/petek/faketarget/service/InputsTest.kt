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

package az.petek.faketarget.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InputsTest {
    @Test
    fun `e-mails are trimmed and lower-cased`() {
        Inputs.email("  Eli.K@Test.KadroHR.com ") shouldBe "eli.k@test.kadrohr.com"
    }

    @Test
    fun `phones lose spacing and punctuation but keep the plus`() {
        Inputs.phone(" +994 (50) 123-45.67 ") shouldBe "+994501234567"
        Inputs.phoneDigits("+994501234567") shouldBe "994501234567"
        Inputs.phoneDigits(" 994501234567") shouldBe "994501234567"
    }

    @Test
    fun `test companies are recognised by the owner's domain only`() {
        Inputs.isTestEmail("a@test.kadrohr.com", "test.kadrohr.com") shouldBe true
        Inputs.isTestEmail("a@test.kadrohr.com", "Test.KadroHR.com") shouldBe true
        Inputs.isTestEmail("a@kadrohr.com", "test.kadrohr.com") shouldBe false
        Inputs.isTestEmail("a@evil-test.kadrohr.com", "test.kadrohr.com") shouldBe false
    }

    @Test
    fun `profiles are validated field by field`() {
        Inputs.validateProfile("Əli", "a@b.az", "+994501234567", "12345678") shouldBe null
        Inputs.validateProfile("", "a@b.az", "+994501234567", "12345678") shouldBe Failure.NAME_REQUIRED
        Inputs.validateProfile("x".repeat(101), "a@b.az", "+994501234567", "12345678") shouldBe Failure.NAME_REQUIRED
        Inputs.validateProfile("Əli", "a@b", "+994501234567", "12345678") shouldBe Failure.EMAIL_INVALID
        Inputs.validateProfile("Əli", "a@b.az", "994501234567", "12345678") shouldBe Failure.PHONE_INVALID
        Inputs.validateProfile("Əli", "a@b.az", "+994501234567", "1234567") shouldBe Failure.PASSWORD_TOO_SHORT
    }

    @Test
    fun `titles are trimmed and limited to 200 characters`() {
        Inputs.validTitle("  Elan  ") shouldBe "Elan"
        Inputs.validTitle("   ") shouldBe null
        Inputs.validTitle("x".repeat(201)) shouldBe null
    }

    @Test
    fun `notification ids resume from their sequence number`() {
        NotificationService.sequenceOf("n12") shouldBe 12
        NotificationService.sequenceOf("7") shouldBe 7
        NotificationService.sequenceOf(null) shouldBe 0
        NotificationService.sequenceOf("garbage") shouldBe 0
        NotificationService.sequenceOf("n-3") shouldBe 0
    }
}
