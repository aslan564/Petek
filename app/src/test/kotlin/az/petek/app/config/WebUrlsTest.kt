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

package az.petek.app.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.net.URI

class WebUrlsTest {
    @Test
    fun `scheme and host are lower-cased and the trailing dot of the host is dropped`() {
        WebUrls.canonical(URI("HTTPS://Staging.Portal.example./app")) shouldBe URI("https://staging.portal.example/app")
    }

    @Test
    fun `port, encoded path, query and fragment are kept exactly`() {
        WebUrls.canonical(URI("http://LocalHost.:8080/test/otp/%2B99450?by=a%40b.test#top")) shouldBe
            URI("http://localhost:8080/test/otp/%2B99450?by=a%40b.test#top")
    }

    @Test
    fun `an already canonical URL is returned unchanged`() {
        val url = URI("https://staging.portal.example/")

        WebUrls.canonical(url) shouldBeSameInstanceAs url
    }

    @Test
    fun `IPv6 hosts keep their brackets`() {
        WebUrls.canonical(URI("HTTP://[::1]:8080/")) shouldBe URI("http://[::1]:8080/")
    }
}
