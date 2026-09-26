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

package az.petek.ownership.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class SiteHostsTest {
    @Test
    fun `the host is lower case without a trailing dot`() {
        SiteHosts.of(URI("https://Staging.Example.COM./login")) shouldBe "staging.example.com"
    }

    @Test
    fun `an IPv6 literal loses its brackets`() {
        SiteHosts.of(URI("http://[::1]:8080/")) shouldBe "::1"
    }

    @Test
    fun `a target that is not http or https has no owner to verify`() {
        shouldThrow<IllegalArgumentException> { SiteHosts.of(URI("ftp://example.com/")) }
    }

    @Test
    fun `IP literals are told apart from names`() {
        SiteHosts.isIpLiteral("10.0.0.5") shouldBe true
        SiteHosts.isIpLiteral("::1") shouldBe true
        SiteHosts.isIpLiteral("staging.example.com") shouldBe false
    }

    @Test
    fun `localhost and names under it are local names, look-alikes are not`() {
        SiteHosts.isLocalName("localhost") shouldBe true
        SiteHosts.isLocalName("shop.localhost") shouldBe true
        SiteHosts.isLocalName("localhost.example.com") shouldBe false
        SiteHosts.isLocalName("mylocalhost") shouldBe false
    }
}
