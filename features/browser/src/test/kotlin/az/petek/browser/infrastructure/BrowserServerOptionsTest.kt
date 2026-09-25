package az.petek.browser.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class BrowserServerOptionsTest {
    @Test
    fun `the server only ever listens on the loopback interface with a random port`() {
        BrowserServerOptions(headless = true).toJson() shouldBe
            """{"headless":true,"host":"127.0.0.1","port":0,"timeout":60000}"""
    }

    @Test
    fun `headed mode, launch timeout and executable path are passed on with JSON escaping`() {
        BrowserServerOptions(
            headless = false,
            launchTimeout = 5.seconds,
            executablePath = Path.of("/opt/my \"chrome\"\\bin\tx"),
        ).toJson() shouldBe
            """{"headless":false,"host":"127.0.0.1","port":0,"timeout":5000,"executablePath":"/opt/my \"chrome\"\\bin\u0009x"}"""
    }
}
