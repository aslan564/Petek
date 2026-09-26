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

import az.petek.campaign.domain.Campaign
import az.petek.core.ids.RunId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.testing.RunnerFixture
import az.petek.orchestration.testing.VirtualClock
import az.petek.orchestration.testing.campaign
import az.petek.orchestration.testing.employees
import az.petek.orchestration.testing.step
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class DefaultRepeatRunnerTest {
    private class RecordingRunner(
        private val outcomes: List<RunOutcome>,
    ) : CampaignRunner {
        val options = CopyOnWriteArrayList<RunOptions>()

        override suspend fun run(
            campaign: Campaign,
            options: RunOptions,
        ): RunSummary {
            this.options += options
            val index = this.options.size
            return RunSummary(RunId("run_$index"), outcomes[index - 1], 1, 0, 0, 0, null, 10)
        }
    }

    @Test
    fun `runs share one group id and get 1-based indexes in order`() =
        runTest {
            val runner = RecordingRunner(List(3) { RunOutcome.PASSED })
            val repeat = DefaultRepeatRunner(runner, SequentialIdGenerator())

            val summaries = repeat.repeat(campaign(), times = 3)

            summaries.map { it.runId.value } shouldBe listOf("run_1", "run_2", "run_3")
            runner.options.map { it.repeatGroup }.toSet() shouldBe setOf("cor_1")
            runner.options.map { it.repeatIndex } shouldBe listOf(1, 2, 3)
            runner.options.all { !it.keepData } shouldBe true
        }

    @Test
    fun `a failed or aborted run does not stop the remaining ones`() =
        runTest {
            val runner = RecordingRunner(listOf(RunOutcome.ABORTED, RunOutcome.FAILED, RunOutcome.PASSED))

            val summaries = DefaultRepeatRunner(runner, SequentialIdGenerator()).repeat(campaign(), times = 3)

            summaries.map { it.outcome } shouldBe listOf(RunOutcome.ABORTED, RunOutcome.FAILED, RunOutcome.PASSED)
        }

    @Test
    fun `keepData and the base options reach every run`() =
        runTest {
            val runner = RecordingRunner(List(2) { RunOutcome.PASSED })
            val repeat = DefaultRepeatRunner(runner, SequentialIdGenerator(), RunOptions(inactivityTimeout = 45.seconds))

            repeat.repeat(campaign(), times = 2, keepData = true)

            runner.options.all { it.keepData && it.inactivityTimeout == 45.seconds } shouldBe true
        }

    @Test
    fun `each repeat call is its own group`() =
        runTest {
            val runner = RecordingRunner(List(4) { RunOutcome.PASSED })
            val repeat = DefaultRepeatRunner(runner, SequentialIdGenerator())

            repeat.repeat(campaign(), times = 2)
            repeat.repeat(campaign(), times = 2)

            runner.options.map { it.repeatGroup } shouldBe listOf("cor_1", "cor_1", "cor_2", "cor_2")
        }

    @Test
    fun `fewer than one repetition is rejected`() =
        runTest {
            shouldThrow<IllegalArgumentException> {
                DefaultRepeatRunner(RecordingRunner(emptyList()), SequentialIdGenerator()).repeat(campaign(), times = 0)
            }
        }

    @Test
    fun `repeated real runs are stored as one group`() =
        runTest {
            val f = RunnerFixture(VirtualClock(testScheduler))
            val repeat = DefaultRepeatRunner(f.runner(), f.ids)

            val summaries = repeat.repeat(campaign(steps = listOf(step("work", employees()))), times = 3)

            summaries shouldHaveSize 3
            val group =
                f.evidence.runList
                    .map { it.repeatGroup }
                    .distinct()
                    .single()!!
            f.evidence.byRepeatGroup(group).map { it.repeatIndex } shouldBe listOf(1, 2, 3)
            f.evidence.byRepeatGroup(group).map { it.runId } shouldBe summaries.map { it.runId }
        }
}
