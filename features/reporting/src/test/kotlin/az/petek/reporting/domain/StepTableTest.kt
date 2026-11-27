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

package az.petek.reporting.domain

import az.petek.evidence.domain.NOT_COVERED
import az.petek.evidence.domain.NOT_REACHED_ACTION
import az.petek.evidence.domain.NotReached
import az.petek.evidence.domain.SKIP_ACTION
import az.petek.evidence.domain.SkipDetail
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.UNCOVERED_ACTION
import az.petek.evidence.domain.WAVE_COVERAGE_ACTION
import az.petek.reporting.ReportTestData.step
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepTableTest {
    @Test
    fun `a run that fails only through steps nobody did counts them as failed steps`() {
        val steps =
            listOf(
                step("read", "a02", StepStatus.PASSED, StepKind.DO),
                step("read", "a02", StepStatus.PASSED, StepKind.WAIT, stepId = "wait_a02"),
                step("approve", null, StepStatus.FAILED, StepKind.SYSTEM, "$NOT_COVERED: nobody", UNCOVERED_ACTION, "unc"),
                step("late", null, StepStatus.FAILED, StepKind.SYSTEM, "$NOT_COVERED: 0 of 2 receivers", WAVE_COVERAGE_ACTION, "gap"),
                step(
                    "check",
                    "a03",
                    StepStatus.FAILED,
                    StepKind.SYSTEM,
                    NotReached.detail(NotReached.NEVER_REACHED, "the run went on"),
                    NOT_REACHED_ACTION,
                    "nr",
                ),
            )

        StepTable.counts(steps) shouldBe StepCounts(passed = 2, failed = 3)
    }

    @Test
    fun `a tester left out or not reached is a row in neither count, and the harness's other records are no rows`() {
        val steps =
            listOf(
                step("read", "a02", StepStatus.SKIPPED, StepKind.SYSTEM, SkipDetail.failedEarlier("mail_timeout"), SKIP_ACTION, "skip"),
                step("read", null, StepStatus.SKIPPED, StepKind.SYSTEM, SkipDetail.noActor("employee[*]"), SKIP_ACTION, "none"),
                step(
                    "read",
                    "a03",
                    StepStatus.SKIPPED,
                    StepKind.SYSTEM,
                    NotReached.detail(NotReached.RUN_ABORTED, "stopped"),
                    NOT_REACHED_ACTION,
                    "nr",
                ),
                step("open", "a04", StepStatus.ERROR, StepKind.SYSTEM, "browser_error: launch", "open_session", "open"),
                step("late", null, StepStatus.PASSED, StepKind.SYSTEM, "1 of 2 receivers could wait", WAVE_COVERAGE_ACTION, "cov"),
            )

        StepTable.rows(steps).map { it.stepId.value } shouldContainExactly listOf("skip", "nr")
        StepTable.counts(steps) shouldBe StepCounts(passed = 0, failed = 0)
    }

    @Test
    fun `a refusal the step expected passes, as in the report`() {
        val refused = step("forbidden", "a02", StepStatus.BLOCKED, StepKind.DO, "permission_denied: 403")

        StepTable.counts(listOf(refused)) shouldBe StepCounts(passed = 1, failed = 0)
    }
}
