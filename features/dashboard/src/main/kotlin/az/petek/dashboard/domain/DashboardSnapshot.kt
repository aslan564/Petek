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

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.evidence.domain.EvidenceTier
import az.petek.evidence.domain.FindingClass
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.RunOutcome
import java.time.Instant

/*
 * The read model of the live dashboard: "who is doing what, right now". Built only from what the harness already
 * observes (monitor notifications and recorded evidence), so the dashboard can never disagree with the evidence store.
 * Every text in it comes from evidence that the agents already redacted (AGENTS.md rule 10); identities contribute
 * their name, role, department and registration mode only, never e-mails, phones or passwords.
 */

/** Everything the page shows at one instant. [version] grows with every applied update, so views can skip repeats. */
data class DashboardSnapshot(
    val version: Long,
    val generatedAt: Instant,
    val run: RunView,
    val counters: DashboardCounters,
    /** Ordered by agent index (`a01`, `a02`, ...). */
    val agents: List<AgentCard>,
    /** Newest first, at most [DashboardState.TIMELINE_LIMIT] entries. */
    val timeline: List<TimelineEntry>,
    /** Newest first, at most [DashboardState.FINDINGS_LIMIT]; [DashboardCounters.findings] counts all of them. */
    val findings: List<FindingView>,
)

/** Where the run is in its life. */
enum class RunPhase {
    /** No run has been seen yet. */
    WAITING,
    RUNNING,

    /** The run ended with an [RunOutcome]. */
    FINISHED,

    /** A replayed run that never recorded its end (the process died half-way). */
    INTERRUPTED,
}

data class RunView(
    val runId: RunId?,
    val campaignName: String?,
    val target: String?,
    val startedAt: Instant?,
    /** Harness-measured run time: live while running, the final duration once finished. */
    val elapsedMs: Long,
    val currentScenarioStep: String?,
    val phase: RunPhase,
    val outcome: RunOutcome?,
    /** Directory of the written report, once the run is finalized. */
    val reportPath: String?,
)

data class DashboardCounters(
    /** Every [AgentState] is present, with 0 when no agent is in it. */
    val agentsByState: Map<AgentState, Int>,
    val stepsPassed: Int,
    /** FAILED, ERROR and BLOCKED step records. */
    val stepsFailed: Int,
    val assertionsPassed: Int,
    val assertionsFailed: Int,
    val assertionsSkipped: Int,
    val events: Int,
    val receiptsReceived: Int,
    val receiptsMissing: Int,
    val findings: Int,
) {
    val agents: Int get() = agentsByState.values.sum()
}

/** One tester as the dashboard shows it. */
data class AgentCard(
    val agentId: AgentId,
    val displayName: String,
    /** Null only while the agent is known from evidence alone (before the run announced its agents). */
    val role: Role?,
    /** Null for the admin, or while unknown. */
    val department: String?,
    val registration: RegistrationMode?,
    val state: AgentState,
    val scenarioStep: String?,
    /** One line, e.g. `click [12] "Elan yarat"`. */
    val lastAction: String?,
    val lastActionAt: Instant?,
    val lastScreenshotArtifactId: ArtifactId?,
    /** Recorded actions (steps other than harness housekeeping and skips). */
    val actionsDone: Int,
    /** Failed steps plus failed assertions attributed to the agent. */
    val failures: Int,
    val lastFailureReason: String?,
)

enum class TimelineKind { STEP, EVENT, RECEIPT, ASSERTION, FINDING, MESSAGE, DIALOG }

enum class TimelineStatus { OK, FAIL, INFO }

/**
 * One line of the run's story. [seq] is the arrival order (unique and increasing within a run); [at] is the time the
 * harness attributes to the fact itself (a step's end, an event's t0, a receipt's t1).
 */
data class TimelineEntry(
    val seq: Long,
    val at: Instant,
    val agentId: AgentId?,
    val kind: TimelineKind,
    val status: TimelineStatus,
    val text: String,
    val scenarioStep: String? = null,
)

/** A judged finding with its three sources: A what the sender did, B what receivers saw, C what the oracle says. */
data class FindingView(
    val findingId: FindingId,
    val findingClass: FindingClass,
    val scenarioStep: String,
    val agentId: AgentId?,
    val a: String?,
    val b: String?,
    val c: String?,
    val note: String,
    val artifactIds: List<ArtifactId>,
    /** How strong the proof is: `ORACLE_CONFIRMED`, `UI_NETWORK` or `LLM_JUDGED` (Faza 10). */
    val evidenceTier: EvidenceTier = EvidenceTier.UI_NETWORK,
)

/** A card with the agent's own recent history (newest first, at most [DashboardState.AGENT_TIMELINE_LIMIT]). */
data class AgentDetail(
    val card: AgentCard,
    val timeline: List<TimelineEntry>,
)

/** The public face of an [Identity]: what the dashboard may show about a tester. */
data class AgentProfile(
    val agentId: AgentId,
    val displayName: String,
    val role: Role,
    val department: String?,
    val registration: RegistrationMode,
) {
    companion object {
        fun of(identity: Identity): AgentProfile =
            AgentProfile(identity.agentId, identity.displayName, identity.role, identity.department, identity.registration)
    }
}

/** A finding with what a root-cause search needs: its step and its evidence, text inline (Faza 11). */
data class FindingBundleView(
    val finding: FindingView,
    val target: String,
    val stepAction: String?,
    val stepStatus: String?,
    val stepDetail: String?,
    val stepStartedAt: Instant?,
    val stepDurationMs: Long?,
    val correlationId: String?,
    val evidence: List<BundleEvidenceView>,
    /** The target's own log lines with the step's correlation id (Faza 14). */
    val serverLog: List<String> = emptyList(),
)

/** One evidence file of a [FindingBundleView]: type, absolute path, and the text of text evidence. */
data class BundleEvidenceView(
    val artifactId: String,
    val type: String,
    val path: String,
    val text: String?,
)
