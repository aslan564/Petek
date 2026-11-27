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

package az.petek.reporting.domain.visual

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.PageLookRecord
import az.petek.evidence.domain.RunResult
import az.petek.reporting.LookTestData.look
import az.petek.reporting.ReportTestData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LookPairingTest {
    private val before = RunId("run_before")
    private val after = RunId("run_after")

    private fun run(
        runId: RunId,
        looks: List<PageLookRecord>,
        minutes: Long = 0,
        group: String? = null,
        hash: String = "c0ffee",
        result: RunResult = RunResult.PASSED,
    ) = LookRunSamples(
        ReportTestData
            .run(runId, startedAt = ReportTestData.START.plusSeconds(minutes * 60), result = result, repeatGroup = group)
            .copy(campaignHash = hash),
        looks,
    )

    private fun plan(
        baseline: List<LookRunSamples>,
        current: List<LookRunSamples>,
        added: Set<String> = emptySet(),
        removed: Set<String> = emptySet(),
    ) = LookPairing.plan(baseline, current, added, removed, maxSamples = 3)

    private fun agents(side: SideSamples?) = side?.all.orEmpty().map { it.runId.value + "/" + it.agentId.value }

    @Test
    fun `looks are paired by step, page and screen, never by tester`() {
        val plans =
            plan(
                listOf(run(before, listOf(look("a01", before), look("a02", before, device = "desktop")))),
                listOf(run(after, listOf(look("a03", after), look("a01", after, device = "desktop")))),
            )

        plans.map { it.key } shouldContainExactly
            listOf(LookKey("public-look", "/", "phone"), LookKey("public-look", "/", "desktop"))
        plans.map { it.decided } shouldContainExactly listOf(null, null)
        plans[0].before?.representative?.agentId shouldBe AgentId("a01")
        plans[0].after?.representative?.agentId shouldBe AgentId("a03")
        plans.map { it.index } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `the representative is the lowest tester id that looked in both runs`() {
        val plans =
            plan(
                listOf(run(before, listOf("a03", "a01", "a02").map { look(it, before) })),
                listOf(run(after, listOf("a05", "a03", "a02").map { look(it, after) })),
            )

        agents(plans.single().before) shouldContainExactly listOf("run_before/a02", "run_before/a01", "run_before/a03")
        agents(plans.single().after) shouldContainExactly listOf("run_after/a02", "run_after/a03", "run_after/a05")
    }

    @Test
    fun `captures of the repeat runs with the same scenario file are samples of their side, at most three`() {
        val sibling = RunId("run_sibling")
        val otherFile = RunId("run_other_file")
        val going = RunId("run_going")
        val later = RunId("run_later")
        val baseline =
            listOf(
                run(before, listOf(look("a01", before)), minutes = 0, group = "grp_1"),
                run(later, listOf(look("a01", later)), minutes = 30, group = "grp_1"),
                run(otherFile, listOf(look("a01", otherFile)), minutes = 5, group = "grp_1", hash = "beef"),
                run(going, listOf(look("a01", going)), minutes = 6, group = "grp_1", result = RunResult.RUNNING),
                run(sibling, listOf(look("a02", sibling), look("a01", sibling)), minutes = 10, group = "grp_1"),
                run(after, listOf(look("a01", after)), minutes = 40, group = "grp_1"),
            )

        val plans = plan(baseline, listOf(run(after, listOf(look("a01", after)), minutes = 40, group = "grp_1")))

        agents(plans.single().before) shouldContainExactly listOf("run_before/a01", "run_sibling/a01", "run_sibling/a02")
        agents(plans.single().after) shouldContainExactly listOf("run_after/a01")
    }

    @Test
    fun `a look in only one run of an unchanged scenario is not comparable, in a changed scenario added or removed`() {
        val baseline = listOf(run(before, listOf(look("a01", before, step = "old-look"))))
        val current = listOf(run(after, listOf(look("a01", after, step = "new-look"))))

        val unchanged = plan(baseline, current)
        val changed = plan(baseline, current, added = setOf("new-look"), removed = setOf("old-look"))

        unchanged.map { Triple(it.key.scenarioStep, it.decided, it.reason) } shouldContainExactly
            listOf(
                Triple("new-look", LookChange.NOT_COMPARABLE, LookReason.MISSING_BEFORE),
                Triple("old-look", LookChange.NOT_COMPARABLE, LookReason.MISSING_NOW),
            )
        changed.map { Triple(it.key.scenarioStep, it.decided, it.reason) } shouldContainExactly
            listOf(Triple("new-look", LookChange.ADDED, null), Triple("old-look", LookChange.REMOVED, null))
    }

    @Test
    fun `another browser or another screen makes a look not comparable`() {
        val browser =
            plan(
                listOf(run(before, listOf(look("a01", before, renderer = "chromium 140.0; Linux x86_64; headless")))),
                listOf(run(after, listOf(look("a01", after)))),
            ).single()
        val screen =
            plan(
                listOf(run(before, listOf(look("a01", before)))),
                listOf(run(after, listOf(look("a01", after, viewport = 390 to 844)))),
            ).single()
        val same = plan(listOf(run(before, listOf(look("a01", before)))), listOf(run(after, listOf(look("a01", after))))).single()

        browser.mismatch shouldBe LookReason.OTHER_BROWSER
        screen.mismatch shouldBe LookReason.OTHER_SCREEN
        same.mismatch.shouldBeNull()
        LookJudge.refusal(FrameCheck.OK, browser.mismatch) shouldBe LookReason.OTHER_BROWSER
    }

    @Test
    fun `a side whose every capture answered 429 or 503 is the surroundings`() {
        val limited =
            plan(
                listOf(run(before, listOf(look("a01", before, status = 429), look("a02", before, status = 503)))),
                listOf(run(after, listOf(look("a01", after)))),
            ).single()
        val partly =
            plan(
                listOf(run(before, listOf(look("a01", before, status = 429), look("a02", before)))),
                listOf(run(after, listOf(look("a01", after)))),
            ).single()

        limited.decided shouldBe LookChange.NOT_COMPARABLE
        limited.reason shouldBe LookReason.SURROUNDINGS
        agents(partly.before) shouldContainExactly listOf("run_before/a02")
        partly.decided.shouldBeNull()
    }
}
