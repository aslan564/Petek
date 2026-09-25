package az.petek.app.panel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContactsTest {
    @Test
    fun `e-mail addresses keep only their domain and everything else stays`() {
        Contacts.masked("a01: emit -> FAILED: oracle: GET /test/tickets/latest?by=tural.18mc.a01@test.kadrohr.com answered 404") shouldBe
            "a01: emit -> FAILED: oracle: GET /test/tickets/latest?by=***@test.kadrohr.com answered 404"
        Contacts.masked("eli+qa@kadro.test, vəli@x.az və mətn") shouldBe "***@kadro.test, ***@x.az və mətn"
        Contacts.masked("heç bir ünvan yoxdur @ burada") shouldBe "heç bir ünvan yoxdur @ burada"
    }
}
