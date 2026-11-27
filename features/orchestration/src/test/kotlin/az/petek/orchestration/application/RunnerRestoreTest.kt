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

package az.petek.orchestration.application

import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserContextLostException
import az.petek.browser.domain.BrowserSession
import az.petek.browser.testing.FakeBrowserSession
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.setupStep
import az.petek.orchestration.testing.step
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** A tester's browser context that crashes mid-run comes back as the same identity, signed in once it had signed in. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunnerRestoreTest {
    /** The first page of a tester crashes on its first click, as a browser server that went down. */
    private class LosingContext(
        val fake: FakeBrowserSession,
    ) : BrowserSession by fake {
        override suspend fun clickSelector(selector: String): Unit =
            throw BrowserContextLostException("Target crashed", BrowserActionException("page closed"))
    }

    @Test
    fun `a crashed context is restored with the storage state the tester saved after its session opened`(
        @TempDir storage: Path,
    ) = runTest {
        val f = RunnerFixture(VirtualClock(testScheduler))
        val crashed = ConcurrentHashMap.newKeySet<String>()
        f.browser.wrap = { fake -> if (crashed.add(fake.label)) LosingContext(fake) else fake }
        f.agents.script = { call, runtime ->
            // a02 signs in during setup and saves its state there, long after its session was opened; a03 never does.
            if (call.scenarioStep == "join" && call.agentId.value == "a02") {
                Files.createDirectories(runtime.storageStatePath.parent)
                Files.writeString(runtime.storageStatePath, """{"cookies":[]}""")
            }
            if (call.scenarioStep == "work") runtime.session.clickSelector("#save")
            ActionOutcome(ActionStatus.SUCCEEDED, "ok")
        }
        val campaign =
            campaign(
                managers = 0,
                employees = 2,
                setup = listOf(setupStep("join", employees())),
                steps = listOf(step("work", employees())),
            )

        val summary =
            f.runner(settings = RunnerSettings(mailDomain = "test.example.test", storageRoot = storage)).run(campaign)

        val saved = storage.resolve(summary.runId.value).resolve("a02.json")
        f.browser.opened
            .filter { it.label == "a02" }
            .map { it.storageState } shouldContainExactly listOf(null, saved)
        f.browser.opened
            .filter { it.label == "a03" }
            .map { it.storageState } shouldContainExactly listOf(null, null)
        val lost = "browser context lost (Target crashed); restored (1) with the same identity"
        f.system(DefaultCampaignRunner.RESTORE_SESSION).map { it.agentId!!.value to it.detail }.sortedBy { it.first } shouldContainExactly
            listOf("a02" to "$lost, storage state loaded", "a03" to "$lost, no storage state loaded (none saved yet)")
        f.steps("work", StepKind.DO).map { it.status }.toSet() shouldBe setOf(StepStatus.PASSED)
        summary.outcome shouldBe RunOutcome.PASSED
    }
}
