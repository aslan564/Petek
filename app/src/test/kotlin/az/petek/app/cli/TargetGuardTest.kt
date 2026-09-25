package az.petek.app.cli

import az.petek.core.security.TargetPolicy
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI

class TargetGuardTest {
    private val policy = TargetPolicy(setOf("kadrohr.com"), allowProduction = false)

    @Test
    fun `a staging target is allowed`() {
        shouldNotThrowAny { TargetGuard.requireAllowed(policy, URI("https://staging.kadrohr.com")) }
    }

    @Test
    fun `a production target is refused with the policy's reason`() {
        val error = shouldThrow<TargetRefusedException> { TargetGuard.requireAllowed(policy, URI("https://kadrohr.com")) }

        error.message shouldContain "PETEK_ALLOW_PRODUCTION"
    }

    @Test
    fun `the same deployment is recognised despite spelling differences`() {
        TargetGuard.origin(URI("https://Staging.KadroHR.com/")) shouldBe TargetGuard.origin(URI("https://staging.kadrohr.com:443"))
        TargetGuard.origin(URI("http://localhost:8080")) shouldBe TargetGuard.origin(URI("http://localhost:8080/"))
    }

    @Test
    fun `other ports, schemes or base paths are other deployments`() {
        (TargetGuard.origin(URI("http://localhost:8080")) == TargetGuard.origin(URI("http://localhost:8081"))) shouldBe false
        (TargetGuard.origin(URI("http://host.test")) == TargetGuard.origin(URI("https://host.test"))) shouldBe false
        (TargetGuard.origin(URI("https://host.test/a")) == TargetGuard.origin(URI("https://host.test/b"))) shouldBe false
    }

    @Test
    fun `a run is only torn down through its own target`() {
        shouldNotThrowAny { TargetGuard.requireRunTarget("https://staging.kadrohr.com/", URI("https://staging.kadrohr.com")) }

        val error =
            shouldThrow<TargetRefusedException> {
                TargetGuard.requireRunTarget("https://user:pw@old-staging.test", URI("https://staging.kadrohr.com"))
            }

        error.message shouldContain "https://***@old-staging.test"
        error.message shouldNotContain "pw@"
    }
}
