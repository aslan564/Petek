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
