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

import az.petek.core.ids.RunId
import az.petek.reporting.LookTestData.look
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LookJudgeTest {
    private val t = VisualThresholds()
    private val before = look("a01", RunId("run_before"), page = "/qiymetler")
    private val after = look("a01", RunId("run_after"), page = "/qiymetler")

    private fun decide(input: LookJudge.Input) = LookJudge.decide(input, t)

    @Test
    fun `an unchanged page is unchanged`() {
        val decision = decide(LookJudge.Input(before, after))

        decision shouldBe LookJudge.Decision(LookChange.UNCHANGED)
    }

    @Test
    fun `a change seen in every pair of samples is changed`() {
        val input = LookJudge.Input(before, after, changed = true)

        LookJudge.needsOtherPairs(input, t) shouldBe true
        decide(input.copy(othersChanged = true)).change shouldBe LookChange.CHANGED
    }

    @Test
    fun `a change seen in only one pair of samples is not a change`() {
        decide(LookJudge.Input(before, after, changed = true, othersChanged = false)).change shouldBe LookChange.UNCHANGED
    }

    @Test
    fun `a page whose moving part covers more than 30 percent is kept moving`() {
        val moving = decide(LookJudge.Input(before, after, changed = true, movingAfter = 0.31))
        val steady = decide(LookJudge.Input(before, after, changed = true, movingBefore = 0.30))

        moving.change shouldBe LookChange.NOT_COMPARABLE
        moving.reason shouldBe LookReason.KEPT_MOVING
        moving.share shouldBe 0.31
        steady.change shouldBe LookChange.CHANGED
        LookJudge.needsOtherPairs(LookJudge.Input(before, after, changed = true, movingAfter = 0.31), t) shouldBe false
    }

    @Test
    fun `a page mostly ignored is not comparable, never unchanged`() {
        val decision = decide(LookJudge.Input(before, after, ignoredBefore = 0.61))

        decision.change shouldBe LookChange.NOT_COMPARABLE
        decision.reason shouldBe LookReason.MOSTLY_IGNORED
        decision.share shouldBe 0.61
    }

    @Test
    fun `a changed page that did not settle is not comparable`() {
        val unsettled = after.copy(settled = false, unsettled = listOf("network", "images"))

        decide(LookJudge.Input(before, unsettled, changed = true)).reason shouldBe LookReason.NOT_SETTLED
        decide(LookJudge.Input(before, unsettled)).change shouldBe LookChange.UNCHANGED
    }

    @Test
    fun `a page that now lands on another path or answers another status is changed`() {
        val landed = decide(LookJudge.Input(before, after.copy(landedPath = "/giris"), movingAfter = 0.9))
        val status = decide(LookJudge.Input(before, after.copy(status = 404)))

        landed.change shouldBe LookChange.CHANGED
        landed.facts shouldContainExactly listOf(LookFact(LookFactKind.LANDED, "/qiymetler", "/giris"))
        status.change shouldBe LookChange.CHANGED
        status.facts shouldContainExactly listOf(LookFact(LookFactKind.STATUS, "200", "404"))
        LookJudge.needsOtherPairs(LookJudge.Input(before, after.copy(status = 404), changed = true), t) shouldBe false
    }

    @Test
    fun `a height change alone is a fact, not a change`() {
        val taller = decide(LookJudge.Input(before.copy(pageHeight = 2_310), after.copy(pageHeight = 2_430)))
        val alike = decide(LookJudge.Input(before.copy(pageHeight = 2_310), after.copy(pageHeight = 2_315)))

        taller.change shouldBe LookChange.UNCHANGED
        taller.facts shouldContainExactly listOf(LookFact(LookFactKind.HEIGHT, "2310", "2430"))
        alike.facts.shouldBeEmpty()
    }

    @Test
    fun `different fonts and tester counts are named on a changed look`() {
        val decision =
            decide(
                LookJudge.Input(
                    before.copy(fonts = listOf("Demo Serif 400 normal", "Demo Sans 400 normal"), testers = 8),
                    after.copy(fonts = listOf("Demo Sans 400 normal", "Demo Sans 700 normal"), testers = 10),
                    changed = true,
                ),
            )

        decision.change shouldBe LookChange.CHANGED
        decision.facts shouldContainExactly
            listOf(
                LookFact(LookFactKind.FONTS, "Demo Serif 400 normal", "Demo Sans 700 normal"),
                LookFact(LookFactKind.TESTERS, "8", "10"),
            )
    }

    @Test
    fun `a frame whose hash changed is altered evidence and a missing frame no image`() {
        val altered = decide(LookJudge.Input(before, after, frames = FrameCheck.ALTERED, mismatch = LookReason.OTHER_BROWSER))
        val missing = decide(LookJudge.Input(before, after, frames = FrameCheck.MISSING, changed = true))

        altered.change shouldBe LookChange.NOT_COMPARABLE
        altered.reason shouldBe LookReason.EVIDENCE_ALTERED
        missing.reason shouldBe LookReason.NO_IMAGE
    }
}
