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

package az.petek.scenarios.domain

import az.petek.core.security.Secret
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SecretRedactorTest {
    @Test
    fun `known secret values are masked wherever they appear`() {
        val redactor = SecretRedactor(listOf(Secret("s3cr3t-token-value"), Secret("Parol-123456")))

        val text = redactor.redact("typed Parol-123456 and sent s3cr3t-token-value twice: s3cr3t-token-value")

        text shouldBe "typed *** and sent *** twice: ***"
    }

    @Test
    fun `very short secrets are ignored so ordinary words survive`() {
        SecretRedactor(listOf(Secret("ab"))).redact("about a tab") shouldBe "about a tab"
    }

    @Test
    fun `key value pairs that look secret are masked`() {
        val redactor = SecretRedactor()

        redactor.redact("password=hunter22 token: abcdef X-Test-Token: xyz api_key=\"k-1\"") shouldBe
            "password=*** token: *** X-Test-Token: *** api_key=\"***\""
    }

    @Test
    fun `secret looking JSON fields are masked too`() {
        SecretRedactor().redact("{\"password\": \"hunter22\", \"token\":\"abc123\", \"name\": \"Əli\"}") shouldBe
            "{\"password\": \"***\", \"token\":\"***\", \"name\": \"Əli\"}"
    }

    @Test
    fun `bearer tokens and Anthropic keys are masked`() {
        val text = SecretRedactor().redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.x.y and sk-ant-api03-AbCdEf123456")

        text shouldNotContain "eyJhbGci"
        text shouldNotContain "AbCdEf123456"
    }

    @Test
    fun `the key formats of every AI provider are masked`() {
        val keys =
            listOf(
                "sk-proj-Ab12Cd34Ef56Gh78Ij90Kl",
                "sk-or-v1-0123456789abcdef0123",
                "sk-0123456789ABCDEFabcdef",
                // Assembled at run time so secret scanners do not take the fixture for a real key.
                "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q",
                "gsk_0123456789abcdefABCDEF",
                "xai-0123456789abcdefABCDEF",
                "hf_0123456789abcdefABCDEF",
            )

        val text = SecretRedactor().redact(keys.joinToString(" and "))

        keys.forEach { text shouldNotContain it.takeLast(12) }
    }

    @Test
    fun `ordinary words that start like a key prefix stay`() {
        SecretRedactor().redact("sk-lab and hf_x are short") shouldBe "sk-lab and hf_x are short"
    }

    @Test
    fun `placeholders and already masked values stay readable`() {
        val redactor = SecretRedactor()

        redactor.redact("type [5] \"{self.password}\"; password={self.password}; fill register.password \"***\"; password: ***") shouldBe
            "type [5] \"{self.password}\"; password={self.password}; fill register.password \"***\"; password: ***"
    }

    @Test
    fun `text without secrets is unchanged`() {
        val text = "click [12] \"Elan yarat\" -> PASSED; observed: Elan dərc olundu"

        SecretRedactor().redact(text) shouldBe text
    }
}
