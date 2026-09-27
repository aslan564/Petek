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

package az.petek.scenarios.domain

import az.petek.evidence.domain.FindingClass
import java.time.Instant

/**
 * The surprises code has already decided, so triage asks no model about them (pure). A surprise whose findings are
 * all [FindingClass.SITE_CHECK] (a check made by code on what the browser saw: broken links and images, console
 * errors, slow requests, a page wider than a phone, a missing title, someone else's page opened) is a defect of the
 * site: [TriageCategory.SYSTEM_BUG], with full confidence and no change to the scenario. A surprise the agent reported
 * itself, or one with any other finding, still needs a model's judgement.
 */
object CodeTriage {
    /** The "model" named on a verdict code made. */
    const val MODEL = "code"

    fun decides(
        surprise: Surprise,
        evidence: RunEvidence,
    ): Boolean {
        if (surprise.kind == SurpriseKind.PROBLEM_REPORTED) return false
        val findings = evidence.findings.filter { it.findingId in surprise.evidence.findingIds }
        return findings.isNotEmpty() && findings.all { it.findingClass == FindingClass.SITE_CHECK }
    }

    /** The verdict for a surprise [decides] accepts, resting on everything the surprise gathered. */
    fun verdict(
        surprise: Surprise,
        scenarioVersionId: ScenarioVersionId,
        at: Instant,
    ): TriageVerdict =
        TriageVerdict(
            surpriseId = surprise.id,
            runId = surprise.runId,
            scenarioVersionId = scenarioVersionId,
            category = TriageCategory.SYSTEM_BUG,
            rationale = RATIONALE,
            confidence = 1.0,
            basedOn = surprise.evidence.refs,
            proposedChange = null,
            model = MODEL,
            decidedAt = at,
        )

    const val RATIONALE =
        "Decided by code, not by a model: a check made by code on what the browser saw failed, so this is a defect of the " +
            "site to report; the scenario stays as it is."
}
