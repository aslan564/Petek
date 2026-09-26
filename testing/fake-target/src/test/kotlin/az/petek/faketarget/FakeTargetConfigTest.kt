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

package az.petek.faketarget

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeTargetConfigTest {
    @Test
    fun `defaults describe a correct target`() {
        val config = FakeTargetConfig()
        config.testToken shouldBe "dev-token"
        config.testMailDomain shouldBe "test.kadrohr.com"
        config.requirePhoneOtp shouldBe true
        config.notificationDelay shouldBe 0.seconds
        config.bugs shouldBe emptySet()
    }

    @Test
    fun `invalid settings are refused`() {
        shouldThrow<IllegalArgumentException> { FakeTargetConfig(testToken = " ") }
        shouldThrow<IllegalArgumentException> { FakeTargetConfig(testMailDomain = "@test.kadrohr.com") }
        shouldThrow<IllegalArgumentException> { FakeTargetConfig(notificationDelay = (-1).milliseconds) }
        shouldThrow<IllegalArgumentException> { FakeTargetConfig(raceWindow = 0.seconds) }
    }

    @Test
    fun `the token never shows up in toString`() {
        val text = FakeTargetConfig(testToken = "super-secret", bugs = setOf(FakeBug.WRONG_TICKET_STATUS)).toString()
        text shouldNotContain "super-secret"
        text shouldContain "testToken=***"
        text shouldContain "WRONG_TICKET_STATUS"
    }

    @Test
    fun `main reads its settings from the environment`() {
        val options =
            FakeTargetOptions.fromEnvironment(
                mapOf(
                    "PETEK_TEST_TOKEN" to "tok",
                    "PETEK_MAIL_DOMAIN" to "qa.example.az",
                    "FAKE_TARGET_PORT" to "9090",
                    "FAKE_TARGET_MAIL_PORT" to "9025",
                    "FAKE_TARGET_PHONE_OTP" to "FALSE",
                    "FAKE_TARGET_NOTIFICATION_DELAY_MS" to "250",
                    "FAKE_TARGET_BUGS" to "race_double_approve, DROP_NOTIFICATION_FOR_ONE_USER",
                    "FAKE_TARGET_RACE_WINDOW_MS" to "15000",
                ),
            )
        options.config.raceWindow shouldBe 15.seconds
        options.port shouldBe 9090
        options.mailPort shouldBe 9025
        options.config.testToken shouldBe "tok"
        options.config.testMailDomain shouldBe "qa.example.az"
        options.config.requirePhoneOtp shouldBe false
        options.config.notificationDelay shouldBe 250.milliseconds
        options.config.bugs shouldBe setOf(FakeBug.RACE_DOUBLE_APPROVE, FakeBug.DROP_NOTIFICATION_FOR_ONE_USER)
    }

    @Test
    fun `main falls back to the documented defaults and rejects nonsense`() {
        val defaults = FakeTargetOptions.fromEnvironment(mapOf("PETEK_TEST_TOKEN" to " "))
        defaults.port shouldBe 18080
        defaults.mailPort shouldBe 18025
        defaults.config shouldBe FakeTargetConfig()

        shouldThrow<IllegalArgumentException> { FakeTargetOptions.fromEnvironment(mapOf("FAKE_TARGET_BUGS" to "NO_SUCH_BUG")) }
        shouldThrow<IllegalArgumentException> { FakeTargetOptions.fromEnvironment(mapOf("FAKE_TARGET_PORT" to "70000")) }
        shouldThrow<IllegalArgumentException> { FakeTargetOptions.fromEnvironment(mapOf("FAKE_TARGET_PHONE_OTP" to "maybe")) }
        shouldThrow<IllegalArgumentException> { FakeTargetOptions.fromEnvironment(mapOf("FAKE_TARGET_NOTIFICATION_DELAY_MS" to "-5")) }
        shouldThrow<IllegalArgumentException> { FakeTargetOptions.fromEnvironment(mapOf("FAKE_TARGET_RACE_WINDOW_MS" to "0")) }
        shouldThrow<IllegalArgumentException> { FakeTargetOptions.fromEnvironment(mapOf("FAKE_TARGET_RACE_WINDOW_MS" to "soon")) }
    }
}
