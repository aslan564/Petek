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
    fun `bearer tokens and Anthropic keys are masked`() {
        val text = SecretRedactor().redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.x.y and sk-ant-api03-AbCdEf123456")

        text shouldNotContain "eyJhbGci"
        text shouldNotContain "AbCdEf123456"
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
