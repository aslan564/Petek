package az.petek.reporting.domain

import az.petek.evidence.domain.StepStatus
import az.petek.reporting.ReportTestData.step
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FailureKeysTest {
    @Test
    fun `a key leading the detail is found`() {
        FailureKeys.find("mail_timeout: no verification e-mail within 60s") shouldBe "mail_timeout"
    }

    @Test
    fun `a key inside the detail is found`() {
        FailureKeys.find("agent stopped (loop_detected) after 3 repeats") shouldBe "loop_detected"
    }

    @Test
    fun `the first key wins when several are present`() {
        FailureKeys.find("otp_rejected, then login_failed") shouldBe "otp_rejected"
    }

    @Test
    fun `a key never matches inside a longer word`() {
        FailureKeys.find("mail_timeout") shouldBe "mail_timeout"
        FailureKeys.find("TimeoutError: waiting for selector").shouldBeNull()
        FailureKeys.find("step_limits were raised").shouldBeNull()
    }

    @Test
    fun `a plain timeout is still a key`() {
        FailureKeys.find("Navigation timeout of 30000 ms exceeded") shouldBe "timeout"
    }

    @Test
    fun `an unknown snake case key leading the detail is recognised`() {
        FailureKeys.find("quota_exceeded: 429 from target") shouldBe "quota_exceeded"
        FailureKeys.find("[captcha_shown]: cannot continue") shouldBe "captcha_shown"
    }

    @Test
    fun `free text without a key has none`() {
        FailureKeys.find("element not found").shouldBeNull()
        FailureKeys.find("note: something").shouldBeNull()
        FailureKeys.find("").shouldBeNull()
        FailureKeys.find(null).shouldBeNull()
    }

    @Test
    fun `completed steps have no failure key even when their detail mentions one`() {
        FailureKeys.of(step("join", "a02", StepStatus.PASSED, detail = "mail_timeout on first try")).shouldBeNull()
        FailureKeys.of(step("join", "a02", StepStatus.SKIPPED, detail = "mail_timeout")).shouldBeNull()
    }

    @Test
    fun `failed and errored steps report the key of their detail`() {
        FailureKeys.of(step("join", "a02", StepStatus.FAILED, detail = "otp_rejected")) shouldBe "otp_rejected"
        FailureKeys.of(step("join", "a02", StepStatus.ERROR, detail = "browser_error: crashed")) shouldBe "browser_error"
        FailureKeys.of(step("join", "a02", StepStatus.FAILED, detail = "unclear")).shouldBeNull()
    }

    @Test
    fun `a blocked step is blocked unless it names a more specific key`() {
        FailureKeys.of(step("join", "a02", StepStatus.BLOCKED, detail = "no progress for 120s")) shouldBe FailureKeys.BLOCKED
        FailureKeys.of(step("join", "a02", StepStatus.BLOCKED, detail = null)) shouldBe FailureKeys.BLOCKED
        FailureKeys.of(step("join", "a02", StepStatus.BLOCKED, detail = "mail_timeout while blocked")) shouldBe "mail_timeout"
    }
}
