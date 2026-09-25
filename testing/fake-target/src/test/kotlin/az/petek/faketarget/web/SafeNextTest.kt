package az.petek.faketarget.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SafeNextTest {
    @Test
    fun `same-site paths are kept`() {
        safeNext("/") shouldBe "/"
        safeNext("/tickets/t1?tab=history") shouldBe "/tickets/t1?tab=history"
        safeNext("/announcements%2Fa1") shouldBe "/announcements%2Fa1"
    }

    @Test
    fun `anything a browser could turn into another origin is refused`() {
        listOf(
            null,
            "",
            "tickets",
            "https://evil.example/",
            "//evil.example",
            "/\\evil.example",
            "/\t/evil.example",
            "/\n/evil.example",
            "/\r/evil.example",
            " //evil.example",
            "/ /evil.example",
            "/\u0000/evil.example",
        ).forEach { safeNext(it) shouldBe null }
    }
}
