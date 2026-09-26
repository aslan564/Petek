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

package az.petek.explorer.application

import az.petek.browser.domain.HttpProbeResult
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.SiteOrigin
import az.petek.explorer.support.FakeSite
import az.petek.explorer.support.Models
import az.petek.explorer.testing.InMemoryExplorationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path

class CompareExplorationsUseCaseTest {
    private val repository = InMemoryExplorationRepository()
    private val compare = CompareExplorationsUseCase(repository)

    @Test
    fun `the latest diff compares the two newest versions of the target`() =
        runTest {
            compare.latest(Models.TARGET).shouldBeNull()
            repository.saveModel(Models.kadro(1))
            compare.latest(Models.TARGET).shouldBeNull()
            val v2 = Models.kadro(2)
            repository.saveModel(v2.copy(pages = v2.pages.filter { it.urlPattern != "/announcements" }))
            repository.saveModel(Models.kadro(3))

            val diff = compare.latest(Models.TARGET).shouldNotBeNull()

            diff.fromVersion shouldBe 2
            diff.toVersion shouldBe 3
            diff.addedPages.map { it.urlPattern } shouldContainExactly listOf("/announcements")
            compare.between(Models.TARGET, 1, 3).isEmpty shouldBe true
            shouldThrow<IllegalArgumentException> { compare.between(Models.TARGET, 1, 9) }.message shouldContain "No site model v9"
        }

    @Test
    fun `partial models of unfinished explorations are left out of the latest diff`() =
        runTest {
            repository.saveModel(Models.kadro(1))
            val v2 = Models.kadro(2)
            repository.saveModel(v2.copy(pages = v2.pages.take(1), partial = true))
            compare.latest(Models.TARGET).shouldBeNull()
            repository.saveModel(Models.kadro(3))

            val diff = compare.latest(Models.TARGET).shouldNotBeNull()

            diff.fromVersion shouldBe 1
            diff.toVersion shouldBe 3
            diff.isEmpty shouldBe true
        }
}

class ReadOnlyBrowserSessionTest {
    private val site = FakeSite(URI("https://kadro.test"))
    private val inner = site.session()
    private val session = ReadOnlyBrowserSession(inner, SiteOrigin.of(URI("https://kadro.test")))

    @Test
    fun `looking is allowed on the target's origin`() =
        runTest {
            site.page("/tickets", "T")

            session.navigate("/tickets")
            session.navigate("https://kadro.test/tickets")
            session.request("GET", "/tickets").status shouldBe 200
            session.snapshot().title shouldBe "T"

            inner.actions shouldContainExactly listOf("navigate /tickets", "navigate https://kadro.test/tickets", "request GET /tickets")
        }

    @Test
    fun `acting, writing requests, leaving the origin and closing are refused`() =
        runTest {
            val refusals =
                listOf<suspend () -> Unit>(
                    { session.click(1) },
                    { session.fill(1, "x") },
                    { session.select(1, "x") },
                    { session.clickSelector("#x") },
                    { session.fillSelector("#x", "x") },
                    { session.selectSelector("#x", "x") },
                    { session.saveStorageState(Path.of("state.json")) },
                    { session.request("POST", "/tickets") },
                    { session.request("GET", "/tickets", "{}") },
                    { session.request("GET", "https://other.test/x") },
                    { session.navigate("https://other.test/") },
                    { session.navigate("//other.test/x") },
                    { session.navigate("http://kadro.test/") },
                    { session.close() },
                )

            refusals.forEach { action -> shouldThrow<IllegalStateException> { action() }.message shouldContain "read-only" }
            inner.actions shouldBe emptyList()
            inner.recorder.closed shouldBe false
        }

    @Test
    fun `the address that was checked is exactly the address passed on`() =
        runTest {
            site.page("/tickets", "T")

            session.navigate("  /tickets\n")
            session.request("get", " /tickets ")

            inner.actions shouldContainExactly listOf("navigate /tickets", "request GET /tickets")
        }

    @Test
    fun `the refusal does not depend on what the wrapped session would answer`() =
        runTest {
            inner.recorder.httpResponses["POST /x"] = HttpProbeResult(200, "")
            shouldThrow<IllegalStateException> { session.request("post", "/x") }
        }
}

class FlowExplorationObserverTest {
    @Test
    fun `events are published to collectors and replayed to late subscribers without blocking the explorer`() =
        runTest {
            val observer = FlowExplorationObserver(replay = 2, buffer = 0)
            val header = { seq: Long -> EventHeader(ExplorationId("exp_1"), seq, Models.AT) }

            (1L..5L).forEach { observer.onEvent(ExplorationEvent.PhaseStarted(header(it), ExplorationPhase.ANONYMOUS, emptyList())) }

            observer.events
                .take(2)
                .toList()
                .map { it.header.seq } shouldContainExactly listOf(4L, 5L)
            observer.events
                .first()
                .header.seq shouldBe 4L
        }
}

class CampaignYamlWriterTest {
    @Test
    fun `quoting escapes backslashes, quotes and control characters`() {
        CampaignYamlWriter.quote("a\"b\\c\nd\te\u0001") shouldBe "\"a\\\"b\\\\c\\nd\\te\\u0001\""
        CampaignYamlWriter.quote("Pətək: [x] # y") shouldBe "\"Pətək: [x] # y\""
    }
}
