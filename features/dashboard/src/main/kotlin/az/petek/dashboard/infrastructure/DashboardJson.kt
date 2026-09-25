package az.petek.dashboard.infrastructure

import az.petek.dashboard.domain.AgentCard
import az.petek.dashboard.domain.AgentDetail
import az.petek.dashboard.domain.DashboardSnapshot
import az.petek.dashboard.domain.FindingView
import az.petek.dashboard.domain.TimelineEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The page's wire format. Times are epoch milliseconds so the page does its own arithmetic ("12 s ago", the running
 * timer) without parsing; enums travel by name, roles and registration modes by their campaign keys. Only fields the
 * page shows are sent: never identities' contact data, never secrets.
 */
internal object DashboardJson {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    fun snapshot(
        snapshot: DashboardSnapshot,
        reportReady: Boolean,
    ): String = json.encodeToString(SnapshotJson.serializer(), snapshot.toJson(reportReady))

    fun detail(detail: AgentDetail): String =
        json.encodeToString(AgentDetailJson.serializer(), AgentDetailJson(detail.card.toJson(), detail.timeline.map { it.toJson() }))

    private fun DashboardSnapshot.toJson(reportReady: Boolean) =
        SnapshotJson(
            version = version,
            generatedAtMs = generatedAt.toEpochMilli(),
            run =
                RunJson(
                    runId = run.runId?.value,
                    campaignName = run.campaignName,
                    target = run.target,
                    startedAtMs = run.startedAt?.toEpochMilli(),
                    elapsedMs = run.elapsedMs,
                    step = run.currentScenarioStep,
                    phase = run.phase.name,
                    outcome = run.outcome?.name,
                ),
            report = ReportJson(reportReady, if (reportReady) REPORT_URL else null),
            counters =
                CountersJson(
                    agents = counters.agents,
                    agentsByState = counters.agentsByState.mapKeys { it.key.name },
                    stepsPassed = counters.stepsPassed,
                    stepsFailed = counters.stepsFailed,
                    assertionsPassed = counters.assertionsPassed,
                    assertionsFailed = counters.assertionsFailed,
                    assertionsSkipped = counters.assertionsSkipped,
                    events = counters.events,
                    receiptsReceived = counters.receiptsReceived,
                    receiptsMissing = counters.receiptsMissing,
                    findings = counters.findings,
                ),
            agents = agents.map { it.toJson() },
            timeline = timeline.map { it.toJson() },
            findings = findings.map { it.toJson() },
        )

    private fun AgentCard.toJson() =
        AgentJson(
            id = agentId.value,
            name = displayName,
            role = role?.key,
            department = department,
            registration = registration?.key,
            state = state.name,
            step = scenarioStep,
            lastAction = lastAction,
            lastActionAtMs = lastActionAt?.toEpochMilli(),
            screenshot = lastScreenshotArtifactId?.value,
            actions = actionsDone,
            failures = failures,
            lastFailure = lastFailureReason,
        )

    private fun TimelineEntry.toJson() =
        EntryJson(
            seq = seq,
            atMs = at.toEpochMilli(),
            agentId = agentId?.value,
            kind = kind.name,
            status = status.name,
            text = text,
            step = scenarioStep,
        )

    private fun FindingView.toJson() =
        FindingJson(
            id = findingId.value,
            findingClass = findingClass.name,
            step = scenarioStep,
            agentId = agentId?.value,
            a = a,
            b = b,
            c = c,
            note = note,
            artifacts = artifactIds.map { it.value },
        )

    const val REPORT_URL = "/report/"
}

@Serializable
internal data class SnapshotJson(
    val version: Long,
    val generatedAtMs: Long,
    val run: RunJson,
    val report: ReportJson,
    val counters: CountersJson,
    val agents: List<AgentJson>,
    val timeline: List<EntryJson>,
    val findings: List<FindingJson>,
)

@Serializable
internal data class RunJson(
    val runId: String?,
    val campaignName: String?,
    val target: String?,
    val startedAtMs: Long?,
    val elapsedMs: Long,
    val step: String?,
    val phase: String,
    val outcome: String?,
)

@Serializable
internal data class ReportJson(
    val ready: Boolean,
    val url: String?,
)

@Serializable
internal data class CountersJson(
    val agents: Int,
    val agentsByState: Map<String, Int>,
    val stepsPassed: Int,
    val stepsFailed: Int,
    val assertionsPassed: Int,
    val assertionsFailed: Int,
    val assertionsSkipped: Int,
    val events: Int,
    val receiptsReceived: Int,
    val receiptsMissing: Int,
    val findings: Int,
)

@Serializable
internal data class AgentJson(
    val id: String,
    val name: String,
    val role: String?,
    val department: String?,
    val registration: String?,
    val state: String,
    val step: String?,
    val lastAction: String?,
    val lastActionAtMs: Long?,
    val screenshot: String?,
    val actions: Int,
    val failures: Int,
    val lastFailure: String?,
)

@Serializable
internal data class EntryJson(
    val seq: Long,
    val atMs: Long,
    val agentId: String?,
    val kind: String,
    val status: String,
    val text: String,
    val step: String?,
)

@Serializable
internal data class FindingJson(
    val id: String,
    val findingClass: String,
    val step: String,
    val agentId: String?,
    val a: String?,
    val b: String?,
    val c: String?,
    val note: String,
    val artifacts: List<String>,
)

@Serializable
internal data class AgentDetailJson(
    val agent: AgentJson,
    val timeline: List<EntryJson>,
)
