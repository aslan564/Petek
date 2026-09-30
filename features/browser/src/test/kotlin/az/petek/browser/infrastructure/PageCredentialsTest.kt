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

package az.petek.browser.infrastructure

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class PageCredentialsTest {
    private val credentials = PageCredentials(URI("https://app.example.test"))

    @Test
    fun `the credential headers of the page's own calls to the target are kept, the latest of each`() {
        credentials.sent(
            "https://app.example.test/api/me",
            "fetch",
            mapOf("Authorization" to "Bearer first-token-1", "content-type" to "application/json", "accept" to "*/*"),
        )
        credentials.sent("https://app.example.test:443/api/me", "xhr", mapOf("authorization" to "Bearer second-token-2"))
        credentials.sent("https://app.example.test/api/me", "fetch", mapOf("X-CSRF-Token" to "csrf-token-3"))

        credentials.headersFor(URI("https://app.example.test/api/tickets/1/approve")) shouldBe
            mapOf("authorization" to "Bearer second-token-2", "x-csrf-token" to "csrf-token-3")
    }

    @Test
    fun `navigations, images, other sites and blank values teach nothing`() {
        credentials.sent("https://app.example.test/", "document", mapOf("authorization" to "Basic dXNlcjpwYXNz"))
        credentials.sent("https://app.example.test/logo.png", "image", mapOf("authorization" to "Bearer image-token"))
        credentials.sent("https://api.other.test/v1/me", "fetch", mapOf("authorization" to "Bearer other-token"))
        credentials.sent("http://app.example.test/api/me", "fetch", mapOf("authorization" to "Bearer plain-http"))
        credentials.sent("https://app.example.test/api/me", "fetch", mapOf("authorization" to " "))

        credentials.headersFor(URI("https://app.example.test/api/me")).shouldBeEmpty()
    }

    @Test
    fun `what the page sent the target is never sent to another site`() {
        credentials.sent("https://app.example.test/api/me", "fetch", mapOf("authorization" to "Bearer own-token-1"))

        credentials.headersFor(URI("https://api.other.test/v1/me")).shouldBeEmpty()
        credentials.headersFor(URI("https://app.example.test:8443/api/me")).shouldBeEmpty()
        credentials.headersFor(URI("https://app.example.test/api/me")) shouldBe mapOf("authorization" to "Bearer own-token-1")
    }

    @Test
    fun `what the page sent the site's API on its own host goes back only there, apart from what it sent the target`() {
        val both = PageCredentials(URI("https://app.example.test"), URI("https://api.example.test"))
        both.sent("https://app.example.test/me", "fetch", mapOf("x-csrf-token" to "app-csrf-1"))
        both.sent("https://api.example.test/v1/me", "fetch", mapOf("authorization" to "Bearer api-token-1"))
        both.sent("https://cdn.example.test/v1/x", "fetch", mapOf("authorization" to "Bearer cdn-token-1"))

        both.headersFor(URI("https://api.example.test/v1/leave/1/approve")) shouldBe mapOf("authorization" to "Bearer api-token-1")
        both.headersFor(URI("https://app.example.test/admin")) shouldBe mapOf("x-csrf-token" to "app-csrf-1")
        both.headersFor(URI("https://cdn.example.test/v1/x")).shouldBeEmpty()
    }

    @Test
    fun `the whole header and the token without its scheme are masked, short values only as the whole header`() {
        credentials.sent(
            "https://app.example.test/api/me",
            "fetch",
            mapOf("authorization" to "Bearer abc.def.ghi", "x-csrf-token" to "short"),
        )

        credentials.secrets() shouldBe setOf("Bearer abc.def.ghi", "abc.def.ghi")
    }
}
