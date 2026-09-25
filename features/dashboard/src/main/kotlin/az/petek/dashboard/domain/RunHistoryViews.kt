package az.petek.dashboard.domain

import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunResult
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
    /** Null when the LLM provider reports no cost (e.g. the Claude plan). */
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

/** What the "Run et" button asks for: an approved scenario (or a campaign file), how many testers, visible browsers. */
data class RunRequest(
    val scenarioId: String?,
    val campaignPath: String? = null,
    /** Null keeps the scenario's own count. */
    val testers: Int? = null,
    val headful: Boolean = false,
) {
    fun problems(): List<FieldProblem> =
        buildList {
            if (scenarioId.isNullOrBlank() == campaignPath.isNullOrBlank()) {
                add(FieldProblem(SCENARIO, "Run üçün təsdiqlənmiş bir ssenari seçin."))
            }
            if (testers != null && testers !in 1..PanelInstructions.MAX_TESTERS) {
                add(FieldProblem(PanelInstructions.TESTERS, "Tester sayı 1 ilə ${PanelInstructions.MAX_TESTERS} arasında olmalıdır."))
            }
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
