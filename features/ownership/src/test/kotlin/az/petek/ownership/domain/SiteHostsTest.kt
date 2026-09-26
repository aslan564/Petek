/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
