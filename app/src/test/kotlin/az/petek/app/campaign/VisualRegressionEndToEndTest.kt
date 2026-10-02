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

package az.petek.app.campaign

import az.petek.app.config.ConfigLoader
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.testing.CliHarness.Companion.done
import az.petek.app.testing.scriptedLlm
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.LookMaskReason
import az.petek.faketarget.notes.FakeNotesServer
import az.petek.faketarget.notes.NotesBug
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.infrastructure.NoOpMonitorView
import az.petek.reporting.application.CompareRunsUseCase.Baseline
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.VisualGate
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Releases compared by how their pages look (docs/adr/0014), with real Chromium against the fake notes site: its welcome
 * page has a ticking clock and today's date, and a later release moves its sign-up link 40 px lower.
 */
@Tag("e2e")
class VisualRegressionEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private var site: FakeNotesServer? = null

    @AfterEach
    fun stop() {
        site?.close()
    }

    private fun container(site: FakeNotesServer): AppContainer {
        val env =
            dir.resolve("notes.env").also {
                Files.writeString(
                    it,
                    """
                    PETEK_TARGET=${site.baseUrl}
                    PETEK_ORACLE=none
                    PETEK_MAILPIT_URL=http://127.0.0.1:9
                    PETEK_IDENTITY_SECRET=visual-e2e-secret
                    PETEK_BROWSER_HEADLESS=true
                    PETEK_EVIDENCE_DIR=evidence
                    """.trimIndent() + "\n",
                )
            }
        val config = ConfigLoader(emptyMap(), dir, IdentitySecretSource { error("the test names its secret") }).load(env)
        return AppContainer(config, AppOverrides(llm = scriptedLlm { done() }, monitor = NoOpMonitorView))
    }

    private suspend fun AppContainer.release(name: String): RunId {
        val campaignFile = dir.resolve("looks.yaml").also { Files.writeString(it, CAMPAIGN) }
        val campaign = campaigns.execute(campaignFile, knownRunFunctions)
        val summary = campaignRunner(headless = true).run(campaign, RunOptions(release = name))
        summary.outcome shouldBe RunOutcome.PASSED
        return summary.runId
    }

    @Test
    fun `a release that moves the sign-up link looks different on every screen of the welcome page, and only there`() =
        runBlocking<Unit> {
            val site = FakeNotesServer(liveClock = true).start().also { site = it }
            container(site).use { container ->
                val first = container.release("v1")
                val second = container.release("v2")
                site.deploy(setOf(NotesBug.SHIFTED_SIGN_UP))
                val third = container.release("v3")

                // Every page on every screen was looked at, its frames kept as visual evidence; the clock is a time text.
                val looks = container.evidenceQuery.pageLooks(second)
                looks.map { it.page to it.device } shouldContainExactlyInAnyOrder
                    listOf("/" to "phone", "/" to "desktop", "/login" to "phone", "/login" to "desktop")
                looks
                    .filter { it.page == "/" }
                    .forEach { look ->
                        (
                            LookMaskReason.TIME_TEXT in
                                look.frames
                                    .first()
                                    .masks
                                    .map { it.reason }
                        ) shouldBe true
                    }
                container.evidenceQuery
                    .artifacts(second)
                    .count { it.type == ArtifactType.VISUAL } shouldBe looks.sumOf { it.frames.size }

                // The same site twice: the ticking clock and the date never make a page look different.
                val unchanged = container.compareRuns.compare(second, Baseline.Run(first)).comparison
                unchanged.looks shouldHaveSize 4
                unchanged.looks.map { it.change }.toSet() shouldBe setOf(LookChange.UNCHANGED)
                unchanged.regressed shouldBe false

                // The new release: the welcome page changed on both screens, the login page did not.
                val reported = container.compareRuns.compare(third, Baseline.Release("v2"))
                val changed = reported.comparison
                changed.changedLooks.map { it.key.page to it.key.device } shouldContainExactlyInAnyOrder
                    listOf("/" to "phone", "/" to "desktop")
                changed.looks
                    .filter { it.key.page == "/login" }
                    .map { it.change }
                    .toSet() shouldBe setOf(LookChange.UNCHANGED)
                changed.changedLooks.forEach { look -> (look.countedRegions.isNotEmpty() || look.countedBands.isNotEmpty()) shouldBe true }
                // Shown but not counted as worse by default; counted with the fail gate.
                changed.regressed shouldBe false
                container.compareRuns
                    .compare(third, Baseline.Release("v2"), VisualGate.FAIL)
                    .comparison.regressed shouldBe true

                // The page names the change and every picture it links exists.
                val page = reported.files.single { it.fileName.toString() == "compare-${second.value}.html" }
                val html = Files.readString(page)
                html shouldContain "Görünüş"
                val missing =
                    LINK
                        .findAll(html)
                        .map { it.groupValues[1] }
                        .filter { it.endsWith(".png") || it.endsWith(".json") }
                        .filterNot { Files.isRegularFile(page.parent.resolve(it).normalize()) }
                        .toList()
                missing.shouldBeEmpty()
            }
        }

    private companion object {
        val LINK = Regex("(?:href|src)=\"([^\"#]+)\"")

        /** Four visitors look at two pages on a phone and a desktop: one page on one screen each. */
        val CAMPAIGN =
            """
            campaign:
              name: looks
              tenant: none
              testers: 4
              seed: 21
              roles: {reader: 4}
              registration: {guest: 4}
              budget: {max_steps_per_agent: 5, max_minutes: 3}
            setup:
              - id: public-look
                actor: reader[*]
                run: {function: site_health, args: {checks: look, pages: "/,/login", devices: "phone,desktop", share: work}}
            steps:
              - id: home
                actor: reader[n=1]
                run: {function: site_health, args: {checks: console, pages: "/"}}
            """.trimIndent() + "\n"
    }
}
