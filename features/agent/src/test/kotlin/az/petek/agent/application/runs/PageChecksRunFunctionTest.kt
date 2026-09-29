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

package az.petek.agent.application.runs

import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.ActorShare
import az.petek.agent.domain.FailureReason
import az.petek.agent.testing.AgentTestData
import az.petek.agent.testing.RunFunctionFixture
import az.petek.browser.domain.AlternateFact
import az.petek.browser.domain.HttpProbeResult
import az.petek.browser.domain.ImageFact
import az.petek.browser.domain.LinkFact
import az.petek.browser.domain.PageFacts
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** `page_checks`: what a visitor sees on each page, checked by code (Faza 19), and `share: pages`. */
class PageChecksRunFunctionTest {
    private val fixture = RunFunctionFixture(AgentTestData.itEmployee, contractSite = false)
    private val browser = fixture.browser

    private fun clean(
        title: String = "Home page",
        links: List<LinkFact> = emptyList(),
    ) = PageFacts(
        title = title,
        headings = listOf("Welcome"),
        description = "What the site offers",
        language = "en",
        images = listOf(ImageFact("/logo.png", "Logo", loaded = true), ImageFact("/line.png", "", loaded = true)),
        links = links,
        missingAnchors = emptyList(),
    )

    private fun visited(): List<String> = browser.actions.filter { it.startsWith("navigate ") }.map { it.removePrefix("navigate ") }

    @Test
    fun `a clean page passes every check`() =
        runTest {
            browser.facts = clean(links = listOf(LinkFact("Shop", "https://shop.example/store")))
            browser.httpResponses["GET https://shop.example/store"] = HttpProbeResult(200, "")

            val outcome = fixture.run("page_checks")

            outcome.status shouldBe ActionStatus.SUCCEEDED
            outcome.summary shouldContain "nothing wrong"
        }

    @Test
    fun `missing anchors, broken images, missing alt texts, meta gaps and dead links are each reported`() =
        runTest {
            browser.facts =
                PageFacts(
                    title = "",
                    headings = listOf("One", "Two"),
                    description = null,
                    language = null,
                    images = listOf(ImageFact("/broken.png", "Broken", loaded = false), ImageFact("/photo.jpg", null, loaded = true)),
                    links =
                        listOf(
                            LinkFact("Old", "https://gone.example/page"),
                            LinkFact("Shy", "https://refuses.example/"),
                        ),
                    missingAnchors = listOf("#contact"),
                )
            browser.httpResponses["GET https://gone.example/page"] = HttpProbeResult(404, "")
            browser.httpResponses["GET https://refuses.example/"] = HttpProbeResult(403, "")

            val outcome = fixture.run("page_checks")

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.UNHEALTHY_PAGE
            outcome.summary shouldContain "links to #contact, which names no element on the page"
            outcome.summary shouldContain "the image /broken.png does not load"
            outcome.summary shouldContain "1 image(s) without alt text (/photo.jpg)"
            outcome.summary shouldContain "has no title"
            outcome.summary shouldContain "has 2 main headings (h1)"
            outcome.summary shouldContain "has no description"
            outcome.summary shouldContain "does not name its language"
            outcome.summary shouldContain "links to https://gone.example/page, which answers 404"
            outcome.summary shouldNotContain "refuses.example/, which"
            outcome.summary shouldContain "1 link(s) to sites that refuse automated visitors were not judged"
        }

    @Test
    fun `language versions that answer, say their language and name the page back pass, each asked once`() =
        runTest {
            val versions =
                listOf(AlternateFact("az", "/az/about"), AlternateFact("en", "/en/about"), AlternateFact("x-default", "/en/about/"))
            browser.pageFactsByUrl["/az/about"] = clean(title = "Haqqımızda").copy(language = "az", alternates = versions)
            browser.pageFactsByUrl["/en/about"] = clean(title = "About").copy(language = "en-GB", alternates = versions)
            browser.httpResponses["GET /en/about"] = HttpProbeResult(200, "")

            val outcome = fixture.run("page_checks", mapOf("checks" to "mirrors", "pages" to "/az/about"))

            outcome.status shouldBe ActionStatus.SUCCEEDED
            browser.actions.filter { it.startsWith("request GET /en/about") } shouldBe listOf("request GET /en/about")
        }

    @Test
    fun `a version that is missing, in another language or not naming the page back is reported`() =
        runTest {
            browser.pageFactsByUrl["/az/"] =
                clean(title = "Ana səhifə").copy(
                    language = "az",
                    alternates =
                        listOf(
                            AlternateFact("en", "/en/"),
                            AlternateFact("ru", "/ru/"),
                            AlternateFact("de", "/de/"),
                            AlternateFact("fr", "https://fr.example.org/"),
                        ),
                )
            browser.pageFactsByUrl["/en/"] = clean(title = "Home").copy(language = "az", alternates = listOf(AlternateFact("az", "/az/")))
            browser.pageFactsByUrl["/ru/"] = clean(title = "Главная").copy(language = "ru")
            browser.httpResponses["GET /en/"] = HttpProbeResult(200, "")
            browser.httpResponses["GET /ru/"] = HttpProbeResult(200, "")
            browser.httpResponses["GET https://fr.example.org/"] = HttpProbeResult(200, "")

            val outcome = fixture.run("page_checks", mapOf("checks" to "mirrors", "pages" to "/az/"))

            outcome.status shouldBe ActionStatus.FAILED
            outcome.failureReason shouldBe FailureReason.UNHEALTHY_PAGE
            outcome.summary shouldContain "/az/ names /en/ as its en version, but it says it is in az"
            outcome.summary shouldContain "/ru/ does not name /az/ back among its language versions"
            outcome.summary shouldContain "/az/ names /de/ as its de version, which answers 404"
            outcome.summary shouldNotContain "fr.example.org"
            // Another site's version is only asked whether it answers; the tester never leaves the site.
            visited() shouldContainExactly listOf("/az/", "/en/", "/ru/")
        }

    @Test
    fun `two pages with the same title are named`() =
        runTest {
            browser.pageFactsByUrl["/"] = clean(title = "Home")
            browser.pageFactsByUrl["/about"] = clean(title = "Home")

            val outcome = fixture.run("page_checks", mapOf("checks" to "meta", "pages" to "/,/about"))

            outcome.summary shouldContain "/, /about share the title \"Home\""
        }

    @Test
    fun `testers sharing the pages each check their own`() =
        runTest {
            browser.facts = clean()

            fixture.run("page_checks", mapOf("checks" to "meta", "pages" to "/,/a,/b,/c,/d", "share" to "pages"), share = ActorShare(1, 2))

            visited() shouldContainExactly listOf("/a", "/c")
        }

    @Test
    fun `with more testers than pages the pages are dealt round, so every tester checks one`() =
        runTest {
            browser.facts = clean()

            fixture.run("page_checks", mapOf("checks" to "meta", "pages" to "/,/a", "share" to "pages"), share = ActorShare(2, 3))

            visited() shouldContainExactly listOf("/")
        }

    @Test
    fun `site health shares its pages the same way`() =
        runTest {
            fixture.run("site_health", mapOf("checks" to "mobile", "pages" to "/,/a,/b", "share" to "pages"), share = ActorShare(2, 3))

            visited() shouldContainExactly listOf("/b")
        }

    @Test
    fun `testers sharing the links each check every page but ask about only their links`() =
        runTest {
            browser.facts = clean(links = listOf("a", "b", "c", "d").map { LinkFact(it, "https://$it.example/") })
            listOf("a", "b", "c", "d").forEach { browser.httpResponses["GET https://$it.example/"] = HttpProbeResult(200, "") }

            fixture.run("page_checks", mapOf("checks" to "outbound", "pages" to "/,/a", "share" to "links"), share = ActorShare(1, 2))

            visited() shouldContainExactly listOf("/", "/a")
            browser.actions.filter { it.startsWith("request GET https://") } shouldContainExactly
                listOf("request GET https://b.example/", "request GET https://d.example/")
        }

    @Test
    fun `testers sharing the work each check other pages on another device, and the screen is put back`() =
        runTest {
            browser.facts = clean()

            fixture.run("page_checks", mapOf("checks" to "meta", "pages" to "/,/a", "share" to "work"), share = ActorShare(1, 3))

            // Six jobs (two pages on a phone, a tablet and a desktop): the second tester of three takes jobs 1 and 4.
            visited() shouldContainExactly listOf("/", "/a")
            browser.actions.filter { it.startsWith("resize ") } shouldContainExactly listOf("resize 768x1024", "resize 1280x720")
        }

    @Test
    fun `with more testers than jobs every tester still works, the second look without asking about links again`() =
        runTest {
            browser.facts = clean(links = listOf(LinkFact("Shop", "https://shop.example/store")))
            browser.httpResponses["GET https://shop.example/store"] = HttpProbeResult(200, "")

            val first = fixture.run("page_checks", mapOf("pages" to "/", "share" to "work", "devices" to "phone"), share = ActorShare(0, 2))
            val again = fixture.run("page_checks", mapOf("pages" to "/", "share" to "work", "devices" to "phone"), share = ActorShare(1, 2))

            first.status shouldBe ActionStatus.SUCCEEDED
            again.status shouldBe ActionStatus.SUCCEEDED
            visited() shouldContainExactly listOf("/", "/")
            browser.actions.count { it.startsWith("request GET https://") } shouldBe 1
        }

    @Test
    fun `site health measures a page at the width of its job's device`() =
        runTest {
            fixture.run("site_health", mapOf("checks" to "mobile", "pages" to "/", "share" to "work"), share = ActorShare(1, 3))

            // The fake names the measurement "viewport"; the screen changes are "resize".
            browser.actions.filter { it.startsWith("resize ") || it.startsWith("viewport ") } shouldContainExactly
                listOf("resize 768x1024", "viewport 768x1024", "resize 1280x720")
        }

    @Test
    fun `an unknown way of sharing is refused before anything is opened`() =
        runTest {
            val outcome = fixture.run("page_checks", mapOf("share" to "checks"))

            outcome.failureReason shouldBe FailureReason.MISSING_PREREQUISITE
            visited() shouldBe emptyList()
        }
}
