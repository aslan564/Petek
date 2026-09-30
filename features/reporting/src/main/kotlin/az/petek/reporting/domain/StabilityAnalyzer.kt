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

import az.petek.core.ids.AgentId
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict

/**
 * Compares the runs of one `--repeat` group step by step (docs/PLAN.md Faza 5), each run judged by [StepOutcomes]: a
 * step passed or failed in it, and a failure is on the site, the surroundings or the tester's agent. So a step that
 * varies only because agents got lost is [StabilityRow.unsteady], never [StabilityRow.flaky], which is kept for a site
 * that fails a step it passes at other times.
 */
class StabilityAnalyzer {
    /** One row per scenario step in order of first appearance across [runs] (given in repeat order). */
    fun analyze(runs: List<RepeatRunEvidence>): List<StabilityRow> {
        val order = LinkedHashSet<String>()
        runs.forEach { run ->
            run.steps.mapTo(order) { it.scenarioStep }
            run.assertions.mapTo(order) { it.scenarioStep }
        }
        val outcomes = runs.map { StepOutcomes.of(it.steps, it.assertions) }
        return order.map { step ->
            val passed = outcomes.count { step in it.passed }
            val causes = outcomes.mapNotNull { it.failed[step] }
            StabilityRow(
                scenarioStep = step,
                runs = runs.size,
                passed = passed,
                siteFailures = causes.count { it == FailureCause.SITE },
                agentFailures = causes.count { it == FailureCause.AGENT },
                environmentFailures = causes.count { it == FailureCause.ENVIRONMENT },
            )
        }
    }
}
