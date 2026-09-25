package az.petek.app.panel

import az.petek.core.security.TargetPolicy
import az.petek.dashboard.domain.PanelRequestException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI

class PanelTargetsTest {
    private val guarded = TargetPolicy(setOf("kadrohr.com"), allowProduction = false)

    @Test
    fun `an allowed target comes back in its canonical spelling`() {
        PanelTargets.allowed(" HTTPS://Staging.KadroHR.com./login ", guarded, "target") shouldBe URI("https://staging.kadrohr.com/login")
    }

    @Test
    fun `a production host is refused with the switch that allows it, in Azerbaijani, under the named field`() {
        val refusal = shouldThrow<PanelRequestException> { PanelTargets.allowed("https://KadroHR.com./", guarded, "target") }

        refusal.problems.single().field shouldBe "target"
        refusal.problems.single().message shouldContain "'kadrohr.com' istehsal ünvanıdır"
        refusal.problems.single().message shouldContain "PETEK_ALLOW_PRODUCTION=true"
    }

    @Test
    fun `a production host passes when the configuration allows production`() {
        PanelTargets.allowed("https://kadrohr.com", guarded.copy(allowProduction = true), "target") shouldBe URI("https://kadrohr.com")
    }

    @Test
    fun `anything but a full http address without credentials is refused`() {
        listOf("kadrohr.com", "ftp://kadrohr.com", "https://", "not a url", "https://user:secret@kadro.test").forEach { text ->
            val refusal = shouldThrow<PanelRequestException> { PanelTargets.allowed(text, guarded, "target") }
            refusal.problems.single().field shouldBe "target"
            refusal.message.orEmpty().contains("secret") shouldBe false
        }
    }

    @Test
    fun `two addresses are one site when scheme, host and effective port match`() {
        PanelTargets.sameSite(URI("https://kadro.test/login"), URI("https://KADRO.test:443/")) shouldBe true
        PanelTargets.sameSite(URI("http://kadro.test"), URI("https://kadro.test")) shouldBe false
        PanelTargets.sameSite(URI("http://127.0.0.1:18080"), URI("http://127.0.0.1:18081")) shouldBe false
        PanelTargets.site(URI("http://kadro.test/x")) shouldBe "http://kadro.test:80"
    }
}
