/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

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
    fun `a production host cannot pass in another spelling`() {
        listOf("https://kadrohr.com./", "https://KADROHR.COM", "HTTPS://kadrohr.com/app", "https://kadrohr.com.:8443/x?y=1").forEach {
            shouldThrow<TargetRefusedException> { TargetGuard.requireAllowed(policy, URI(it)) }.message shouldContain "production host"
        }
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
