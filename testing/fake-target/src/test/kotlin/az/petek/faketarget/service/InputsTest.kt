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
