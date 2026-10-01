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

import az.petek.evidence.domain.PageLookRecord

/** The representatives' frames as the comparison found them on disk. */
enum class FrameCheck {
    OK,

    /** A file no longer has the hash recorded with it. */
    ALTERED,

    /** A file is missing or cannot be read as an image. */
    MISSING,
}

/**
 * Decides one compared look (docs/adr/0014; code, never a model, AGENTS.md rule 2). The gates, in order:
 *
 * 1. a representative frame altered on disk: [LookReason.EVIDENCE_ALTERED];
 * 2. one missing or unreadable: [LookReason.NO_IMAGE];
 * 3. another browser or system: [LookReason.OTHER_BROWSER]; 4. another screen: [LookReason.OTHER_SCREEN];
 * 5. (the surroundings are decided by [LookPairing]);
 * 6. the page now lands on another path or answers another status: [LookChange.CHANGED], proven by the records;
 * 7. a side's moving and peer cells exceed [VisualThresholds.movingLimit]: [LookReason.KEPT_MOVING];
 * 8. a side's ignored pixels exceed [VisualThresholds.ignoredLimit]: [LookReason.MOSTLY_IGNORED];
 * 9. the representatives differ (a counted region or band) and so does every other pair of samples:
 *    [LookChange.CHANGED], or [LookReason.NOT_SETTLED] when either representative had not finished loading;
 * 10. otherwise [LookChange.UNCHANGED].
 *
 * Facts (height, fonts, tester counts, and the landing path and status) are named with the verdict; only the gates
 * decide it.
 */
object LookJudge {
    data class Input(
        val before: PageLookRecord,
        val after: PageLookRecord,
        val frames: FrameCheck = FrameCheck.OK,
        /** [LookPlan.mismatch]. */
        val mismatch: LookReason? = null,
        /** The representatives' comparison has a counted region or band. */
        val changed: Boolean = false,
        /** Every other pair of samples has one too (true when there is no other pair). */
        val othersChanged: Boolean = true,
        /** Each side's moving and peer cells, as a share of its cells. */
        val movingBefore: Double = 0.0,
        val movingAfter: Double = 0.0,
        /** Each side's ignored pixels, as a share of its capture. */
        val ignoredBefore: Double = 0.0,
        val ignoredAfter: Double = 0.0,
    )

    data class Decision(
        val change: LookChange,
        val reason: LookReason? = null,
        /** The share behind [LookReason.KEPT_MOVING] or [LookReason.MOSTLY_IGNORED]. */
        val share: Double? = null,
        val facts: List<LookFact> = emptyList(),
    )

    /** Gates 1 to 4: what makes a look not comparable before any pixel is decoded. */
    fun refusal(
        frames: FrameCheck,
        mismatch: LookReason?,
    ): LookReason? =
        when (frames) {
            FrameCheck.ALTERED -> LookReason.EVIDENCE_ALTERED
            FrameCheck.MISSING -> LookReason.NO_IMAGE
            FrameCheck.OK -> mismatch
        }

    /** Whether the verdict turns on the other pairs of samples: only gate 9 needs them. */
    fun needsOtherPairs(
        input: Input,
        t: VisualThresholds,
    ): Boolean = input.changed && stage(input, t) == null

    fun decide(
        input: Input,
        t: VisualThresholds,
    ): Decision {
        val facts = facts(input.before, input.after, t)
        refusal(input.frames, input.mismatch)?.let { return Decision(LookChange.NOT_COMPARABLE, it, facts = facts) }
        stage(input, t)?.let { return it.copy(facts = facts) }
        return when {
            input.changed && input.othersChanged && !(input.before.settled && input.after.settled) -> {
                Decision(LookChange.NOT_COMPARABLE, LookReason.NOT_SETTLED, facts = facts)
            }

            input.changed && input.othersChanged -> {
                Decision(LookChange.CHANGED, facts = facts)
            }

            else -> {
                Decision(LookChange.UNCHANGED, facts = facts)
            }
        }
    }

    /** Gates 6 to 8, once the frames are known to be comparable. */
    private fun stage(
        input: Input,
        t: VisualThresholds,
    ): Decision? {
        val moving = maxOf(input.movingBefore, input.movingAfter)
        val ignored = maxOf(input.ignoredBefore, input.ignoredAfter)
        return when {
            input.before.landedPath != input.after.landedPath || input.before.status != input.after.status -> Decision(LookChange.CHANGED)
            moving > t.movingLimit -> Decision(LookChange.NOT_COMPARABLE, LookReason.KEPT_MOVING, moving)
            ignored > t.ignoredLimit -> Decision(LookChange.NOT_COMPARABLE, LookReason.MOSTLY_IGNORED, ignored)
            else -> null
        }
    }

    /** What the two records differ in, in the order the report names it. */
    fun facts(
        before: PageLookRecord,
        after: PageLookRecord,
        t: VisualThresholds,
    ): List<LookFact> =
        buildList {
            if (before.landedPath != after.landedPath) add(LookFact(LookFactKind.LANDED, before.landedPath, after.landedPath))
            if (before.status !=
                after.status
            ) {
                add(LookFact(LookFactKind.STATUS, before.status?.toString().orEmpty(), after.status?.toString().orEmpty()))
            }
            if (kotlin.math.abs(after.pageHeight - before.pageHeight) >= t.heightFact) {
                add(LookFact(LookFactKind.HEIGHT, before.pageHeight.toString(), after.pageHeight.toString()))
            }
            val gone = before.fonts - after.fonts.toSet()
            val new = after.fonts - before.fonts.toSet()
            if (gone.isNotEmpty() || new.isNotEmpty()) add(LookFact(LookFactKind.FONTS, gone.joinToString(", "), new.joinToString(", ")))
            if (before.testers != after.testers) add(LookFact(LookFactKind.TESTERS, before.testers.toString(), after.testers.toString()))
        }
}
