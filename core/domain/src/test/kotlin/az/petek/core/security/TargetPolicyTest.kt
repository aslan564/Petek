package az.petek.core.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI

class TargetPolicyTest {
    private val guarded = TargetPolicy(productionHosts = setOf("kadrohr.com", " WWW.kadrohr.com "), allowProduction = false)

    @Test
    fun `a production host is refused and the refusal names both env variables`() {
        val verdict = guarded.verify(URI("https://kadrohr.com/login")).shouldBeInstanceOf<TargetVerdict.Refused>()

        verdict.reason shouldContain "'kadrohr.com'"
        verdict.reason shouldContain "PETEK_PRODUCTION_HOSTS"
        verdict.reason shouldContain "PETEK_ALLOW_PRODUCTION=true"
    }

    @Test
    fun `production hosts are compared trimmed and without regard to case`() {
        guarded.verify(URI("https://www.KadroHR.com")).shouldBeInstanceOf<TargetVerdict.Refused>()
    }

    @Test
    fun `a production host is allowed when production is allowed explicitly`() {
        guarded.copy(allowProduction = true).verify(URI("https://kadrohr.com")) shouldBe TargetVerdict.Allowed
    }

    @Test
    fun `other hosts, including subdomains of a production host, are allowed`() {
        guarded.verify(URI("https://staging.kadrohr.com")) shouldBe TargetVerdict.Allowed
        guarded.verify(URI("http://localhost:8080")) shouldBe TargetVerdict.Allowed
    }

    @Test
    fun `targets without a host or with another scheme are refused`() {
        guarded.verify(URI("file:///etc/passwd")).shouldBeInstanceOf<TargetVerdict.Refused>().reason shouldContain "no host"
        guarded.verify(URI("ftp://example.com")).shouldBeInstanceOf<TargetVerdict.Refused>().reason shouldContain "http(s)"
    }
}
