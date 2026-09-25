/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.net.URI

class WebUrlsTest {
    @Test
    fun `scheme and host are lower-cased and the trailing dot of the host is dropped`() {
        WebUrls.canonical(URI("HTTPS://Staging.KadroHR.com./app")) shouldBe URI("https://staging.kadrohr.com/app")
    }

    @Test
    fun `port, encoded path, query and fragment are kept exactly`() {
        WebUrls.canonical(URI("http://LocalHost.:8080/test/otp/%2B99450?by=a%40b.test#top")) shouldBe
            URI("http://localhost:8080/test/otp/%2B99450?by=a%40b.test#top")
    }

    @Test
    fun `an already canonical URL is returned unchanged`() {
        val url = URI("https://staging.kadrohr.com/")

        WebUrls.canonical(url) shouldBeSameInstanceAs url
    }

    @Test
    fun `IPv6 hosts keep their brackets`() {
        WebUrls.canonical(URI("HTTP://[::1]:8080/")) shouldBe URI("http://[::1]:8080/")
    }
}
