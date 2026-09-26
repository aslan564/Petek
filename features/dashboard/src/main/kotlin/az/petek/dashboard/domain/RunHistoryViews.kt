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

package az.petek.dashboard.domain

import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunResult
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

// Past runs for the "Hesabatlar" screen: results, cost, reports and the stability of `--repeat` groups.

data class RunSummaryView(
    val runId: RunId,
    val campaignName: String,
    val target: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val durationMs: Long?,
    val result: RunResult,
    val testers: Int,
    val stepsPassed: Int,
    val stepsFailed: Int,
    val assertionsPassed: Int,
    val assertionsFailed: Int,
    val findings: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Null when the LLM provider reports no cost (e.g. a subscription plan). */
    val costUsd: Double?,
    val repeatGroup: String?,
    val repeatIndex: Int?,
    val reportAvailable: Boolean,
    val triaged: Boolean,
    val scenarioId: String?,
)

/** How the steps of a `--repeat` group behaved across its runs. */
data class StabilityView(
    val repeatGroup: String,
    val runs: List<RunId>,
    val steps: List<StepStabilityView>,
)

data class StepStabilityView(
    val scenarioStep: String,
    val runs: Int,
    val passed: Int,
) {
    /** Passed in some runs and failed in others. */
    val flaky: Boolean get() = passed in 1 until runs
}

/**
 * What the "Run et" button asks for: an approved scenario (or a campaign file), how many testers, visible browsers,
 * and optionally the site to run against.
 */
data class RunRequest(
    val scenarioId: String?,
    val campaignPath: String? = null,
    /** Null keeps the scenario's own count. */
    val testers: Int? = null,
    val headful: Boolean = false,
    /**
     * The "Hədəf sayt" of the instruction screen. Null or blank runs against the scenario's own target (the configured
     * `PETEK_TARGET`); another absolute http(s) URL runs against that site as if it were `PETEK_TARGET`, still subject to
     * the backend's target policy.
     */
    val target: String? = null,
) {
    fun problems(): List<FieldProblem> =
        buildList {
            if (scenarioId.isNullOrBlank() == campaignPath.isNullOrBlank()) {
                add(FieldProblem(SCENARIO, "Run üçün təsdiqlənmiş bir ssenari seçin."))
            }
            if (testers != null && testers !in 1..PanelInstructions.MAX_TESTERS) {
                add(FieldProblem(PanelInstructions.TESTERS, "Tester sayı 1 ilə ${PanelInstructions.MAX_TESTERS} arasında olmalıdır."))
            }
            if (!target.isNullOrBlank() && !isWebUrl(target.trim())) {
                add(FieldProblem(PanelInstructions.TARGET, "Hədəf http:// və ya https:// ilə başlayan tam ünvan olmalıdır."))
            }
        }

    private fun isWebUrl(text: String): Boolean {
        val uri =
            try {
                URI(text)
            } catch (_: URISyntaxException) {
                return false
            }
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    companion object {
        const val SCENARIO = "scenario"
    }
}

/** A run the backend accepted; it goes on in the background and shows up on the live board. */
data class RunStartView(
    val runId: RunId,
    val scenarioId: String?,
    val testers: Int,
)

/** What a teardown removed from the target and what it could not; both empty when nothing was left. */
data class TeardownView(
    val runId: RunId,
    val removed: List<String>,
    val failures: List<String>,
)
