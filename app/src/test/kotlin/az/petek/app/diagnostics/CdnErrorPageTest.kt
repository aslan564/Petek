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

package az.petek.app.diagnostics

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

class CdnErrorPageTest {
    private fun answer(
        status: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ) = HttpCheck.Answered(status, null, headers.toMap(), body)

    @Test
    fun `a Cloudflare error page in place of the site is named with its code`() {
        val page =
            answer(
                403,
                CLOUDFLARE_ERROR_1000,
                "server" to "cloudflare",
                "cf-ray" to "a4127321da4ba8a2-RIX",
            )

        CdnErrorPage.of(page) shouldBe "Cloudflare error 1000: DNS points to prohibited IP"
    }

    @Test
    fun `a bot challenge is a block, whatever the status`() {
        CdnErrorPage.of(
            answer(403, "<title>Just a moment...</title>", "server" to "cloudflare", "cf-mitigated" to "challenge"),
        ) shouldContain
            "bot challenge"
    }

    @Test
    fun `the site's own login wall and a page served through a CDN are the site`() {
        CdnErrorPage.of(answer(403, "<title>Giriş | KadroHR</title>", "server" to "nginx")) shouldBe null
        CdnErrorPage.of(answer(200, "<title>Kodcraft Lab</title>", "server" to "cloudflare")) shouldBe null
        CdnErrorPage.of(answer(401, "<title>Sign in</title>", "server" to "cloudflare")) shouldBe null
    }

    @Test
    fun `other firewalls and CDNs are recognised by their pages`() {
        CdnErrorPage.of(answer(403, "<h1>The request could not be satisfied</h1>", "server" to "CloudFront")) shouldContain "CloudFront"
        CdnErrorPage.of(answer(403, "Access Denied - Sucuri WebSite Firewall")) shouldContain "Sucuri"
        CdnErrorPage.of(answer(403, "Request unsuccessful. Incapsula incident ID: 1-2")) shouldContain "Imperva"
    }

    @Test
    fun `the reachability look refuses a site that only shows a CDN error page`() =
        runBlocking<Unit> {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.use {
                    val bytes = CLOUDFLARE_ERROR_1000.toByteArray()
                    it.responseHeaders.add("Server", "cloudflare")
                    it.sendResponseHeaders(403, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.start()
            try {
                HttpProbe().use { http ->
                    val answer = HttpTargetReachability(http).check(URI("http://127.0.0.1:${server.address.port}/"))

                    answer.shouldBeInstanceOf<TargetAnswer.Unreachable>().reason shouldBe
                        "HTTP 403: Cloudflare error 1000: DNS points to prohibited IP"
                }
            } finally {
                server.stop(0)
            }
        }

    private companion object {
        val CLOUDFLARE_ERROR_1000 =
            """
            <!DOCTYPE html><html><head><title>DNS points to prohibited IP | kodcraftlab.com | Cloudflare</title></head>
            <body><div id="cf-wrapper"><div id="cf-error-details"><h1><span class="inline-block">Error</span>
            <span class="code-label">Error 1000</span></h1><h2>DNS points to prohibited IP</h2></div></div></body></html>
            """.trimIndent()
    }
}
