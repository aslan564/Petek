package az.petek.browser.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SecretRedactorTest {
    @Test
    fun `textbox values that are secrets are masked however short they are`() {
        val yaml =
            """
            - text: Şifrə
            - textbox "Şifrə": ab
            - textbox "Ad": ab
            - paragraph: about
            """.trimIndent()

        SecretRedactor.redactAriaSnapshot(yaml, listOf("ab")) shouldBe
            """
            - text: Şifrə
            - textbox "Şifrə": ******
            - textbox "Ad": ******
            - paragraph: about
            """.trimIndent()
    }

    @Test
    fun `quoted YAML values and textbox attributes are understood`() {
        val yaml =
            """
            - textbox "Parol \"köhnə\"" [disabled]: "p: \"x\""
              - textbox "Yeni": keep
            """.trimIndent()

        SecretRedactor.redactAriaSnapshot(yaml, listOf("p: \"x\"")) shouldBe
            """
            - textbox "Parol \"köhnə\"" [disabled]: ******
              - textbox "Yeni": keep
            """.trimIndent()
    }

    @Test
    fun `longer secrets are masked anywhere in the text`() {
        SecretRedactor.redactText("<p>Your password is hunter22, hunter22!</p>", listOf("hunter22")) shouldBe
            "<p>Your password is ******, ******!</p>"
    }

    @Test
    fun `very short secrets do not shred free text`() {
        SecretRedactor.redactText("banana", listOf("an")) shouldBe "banana"
    }

    @Test
    fun `the longest secret wins when secrets overlap`() {
        SecretRedactor.redactText("x secret-long y", listOf("secret", "secret-long")) shouldBe "x ****** y"
    }

    @Test
    fun `no secrets leaves the text untouched`() {
        SecretRedactor.redactAriaSnapshot("- textbox \"Ad\": Aysel", emptyList()) shouldBe "- textbox \"Ad\": Aysel"
        SecretRedactor.redactAriaSnapshot("- textbox \"Ad\": Aysel", listOf("")) shouldBe "- textbox \"Ad\": Aysel"
    }
}
