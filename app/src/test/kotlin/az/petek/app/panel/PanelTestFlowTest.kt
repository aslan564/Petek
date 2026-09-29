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

package az.petek.app.panel

import az.petek.app.testing.FakeBrowserEngine
import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelWaits
import az.petek.app.testing.PanelWaits.exploration
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.ScenarioSource
import az.petek.dashboard.domain.ScenarioStatus
import az.petek.dashboard.domain.TestFlowView
import az.petek.dashboard.domain.TestStage
import az.petek.evidence.domain.RunResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeOneOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** "Test et" (Faza 25.3): explore, draft from that exploration, approve and run in one go, over the production wiring. */
class PanelTestFlowTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()

    @AfterEach
    fun close() = open.forEach { it.close() }

    private fun harness(): PanelHarness = PanelHarness(dir, site = PanelWaits.site(), runs = FakeBrowserEngine()).also { open += it }

    /** The owner's form with nothing but the site, the tester count and the budget: the team is the explorer's to find. */
    private fun PanelHarness.form(testers: Int = 2): PanelInstructions =
        PanelHarness
            .instructions(
                site.base.toString(),
            ).copy(testers = testers, roles = null, registration = null, departments = emptyList())

    private suspend fun PanelHarness.ended(): TestFlowView =
        withTimeout(PanelWaits.TIMEOUT) {
            var view = backend.testFlow()
            while (view == null || !view.stage.isFinal) {
                delay(POLL)
                view = backend.testFlow()
            }
            view
        }

    @Test
    fun `the site is explored, the draft of that exploration approved and run, and the test ends with the run's result`() =
        runBlocking<Unit> {
            val panel = harness()

            val started = panel.backend.startTest(panel.form(testers = 2))

            started.stage shouldBe TestStage.EXPLORING
            started.target shouldBe panel.site.base.toString()
            val ended = panel.ended()
            ended.stage shouldBe TestStage.FINISHED
            ended.result.shouldNotBeNull() shouldBeOneOf listOf(RunResult.PASSED, RunResult.FAILED)
            ended.note.shouldNotBeNull() shouldContain "Test bitdi"
            val explored = panel.backend.exploration().shouldNotBeNull()
            explored.status shouldBe ExplorationStatus.FINISHED
            ended.explorationId shouldBe explored.id
            val version = panel.backend.scenarios().single { it.id == ended.scenarioId }
            version.source shouldBe ScenarioSource.EXPLORER
            version.status shouldBe ScenarioStatus.APPROVED
            version.note shouldContain explored.id
            val run = panel.backend.runs().single()
            run.runId shouldBe ended.runId
            run.scenarioId shouldBe version.id
            run.result shouldBe ended.result
            run.testers shouldBe 2
            panel.backend.reportDirectory(run.runId).shouldNotBeNull()
        }

    @Test
    fun `a test whose exploration is stopped writes no scenario and starts no run`() =
        runBlocking<Unit> {
            val panel = harness()
            panel.llm.explorerGate = CompletableDeferred()
            panel.backend.startTest(panel.form())
            panel.exploration { it.id != "hazırlanır" && it.status == ExplorationStatus.RUNNING }

            panel.backend.cancelExploration() shouldBe true
            val ended = panel.ended()

            ended.stage shouldBe TestStage.STOPPED
            ended.note.shouldNotBeNull() shouldContain "Kəşfiyyat dayandırıldı"
            ended.scenarioId.shouldBeNull()
            ended.runId.shouldBeNull()
            panel.backend.scenarios().shouldBeEmpty()
            panel.backend.runs().shouldBeEmpty()
        }

    @Test
    fun `one test goes at a time, and stopping it stops the part that is going`() =
        runBlocking<Unit> {
            val panel = harness()
            panel.llm.explorerGate = CompletableDeferred()
            panel.backend.startTest(panel.form())

            shouldThrow<PanelConflictException> { panel.backend.startTest(panel.form()) }.message shouldContain "Artıq bir test gedir"
            panel.backend.cancelTest() shouldBe true
            val ended = panel.ended()

            ended.stage shouldBe TestStage.STOPPED
            ended.note shouldBe "Test dayandırıldı."
            panel.exploration { it.status != ExplorationStatus.RUNNING }.status shouldBe ExplorationStatus.CANCELLED
            panel.backend.cancelTest() shouldBe false
            panel.backend.runs().shouldBeEmpty()
        }

    private companion object {
        val POLL = 50.milliseconds
    }
}
