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

package az.petek.dashboard.application

import az.petek.core.ids.RunId
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.AgentProfile
import az.petek.dashboard.domain.DashboardSnapshot
import az.petek.dashboard.domain.DashboardState
import az.petek.dashboard.domain.DashboardUpdate
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.RunOutcome
import java.time.Duration
import java.time.Instant

/**
 * The final dashboard of a run that is no longer live (`petek dashboard <run_id>`), rebuilt from the evidence store
 * by the same reducer that drives the live board, so both always agree. The agents end FAILED when their identity
 * failed, DONE when the run ended, IDLE when it never recorded an end (the run is then shown as interrupted).
 */
suspend fun snapshotFromEvidence(
    run: RunRecord,
    query: EvidenceQuery,
    identities: List<Identity>,
    reportPath: String? = null,
): DashboardSnapshot {
    val replay = EvidenceReplay.load(run, query, identities, reportPath)
    return DashboardState.EMPTY.applyAll(replay.updates).snapshot(replay.end)
}

/**
 * The updates that retell a recorded run in time order. Evidence carries wall time only, so replayed timestamps use
 * the epoch as their monotonic base; a replayed run always ends with [DashboardUpdate.RunEnded], so its elapsed time
 * is the recorded duration and never mixes the two bases.
 *
 * Ordering: steps at their end, artifacts right after their step, events at t0, receipts at t1 (else the event's t0),
 * assertions after their step, findings (judged after the run) at the end.
 */
internal class EvidenceReplay(
    val updates: List<DashboardUpdate>,
    val artifacts: List<ArtifactRecord>,
    val end: HarnessTimestamp,
) {
    private class Timed(
        val at: Instant,
        val order: Int,
        val update: DashboardUpdate,
    )

    companion object {
        suspend fun load(
            run: RunRecord,
            query: EvidenceQuery,
            identities: List<Identity>,
            reportPath: String?,
        ): EvidenceReplay {
            val runId = run.runId
            val steps = query.steps(runId)
            val artifacts = query.artifacts(runId)
            val events = query.events(runId)
            val receipts = query.receipts(runId)
            val assertions = query.assertions(runId)
            val findings = query.findings(runId)
            val stepEnds = steps.associate { it.stepId to it.endedAt }
            val eventTimes = events.associate { it.eventId to it.t0 }
            val lastSeen =
                (steps.map { it.endedAt } + events.map { it.t0 } + receipts.mapNotNull { it.t1 } + run.startedAt).max()
            val end = run.endedAt ?: lastSeen
            val timed =
                buildList {
                    steps.forEach { add(Timed(it.endedAt, STEP, DashboardUpdate.StepRecorded(it, at(it.endedAt)))) }
                    artifacts.forEach {
                        val time = stepEnds[it.stepId] ?: run.startedAt
                        add(Timed(time, AFTER_STEP, DashboardUpdate.ArtifactRecorded(it, at(time))))
                    }
                    events.forEach { add(Timed(it.t0, STEP, DashboardUpdate.EventRecorded(it, at(it.t0)))) }
                    receipts.forEach {
                        val time = it.t1 ?: eventTimes[it.eventId] ?: end
                        add(Timed(time, AFTER_STEP, DashboardUpdate.ReceiptRecorded(it, at(time))))
                    }
                    assertions.forEach {
                        val time = stepEnds[it.stepId] ?: end
                        add(Timed(time, VERDICT, DashboardUpdate.AssertionRecorded(it, at(time))))
                    }
                    findings.forEach { add(Timed(end, JUDGEMENT, DashboardUpdate.FindingRecorded(it, at(end)))) }
                }.sortedWith(compareBy<Timed>({ it.at }, { it.order }))
            val start = at(run.startedAt)
            val finish = at(end)
            val updates =
                buildList {
                    add(DashboardUpdate.RunCreated(run, start))
                    add(DashboardUpdate.RunStarted(runId, identities.map { status(it, AgentState.IDLE, start) }, start))
                    add(DashboardUpdate.AgentsPlanned(runId, identities.map(AgentProfile::of), start))
                    timed.forEach { add(it.update) }
                    identities.forEach { add(DashboardUpdate.AgentUpdated(status(it, finalState(it, run), finish), finish)) }
                    add(ended(runId, run, end, reportPath, finish))
                }
            return EvidenceReplay(updates, artifacts, finish)
        }

        private fun ended(
            runId: RunId,
            run: RunRecord,
            end: Instant,
            reportPath: String?,
            finish: HarnessTimestamp,
        ) = DashboardUpdate.RunEnded(
            runId = runId,
            outcome = outcomeOf(run.result),
            durationMs = Duration.between(run.startedAt, end).toMillis().coerceAtLeast(0),
            reportPath = reportPath,
            at = finish,
        )

        private fun status(
            identity: Identity,
            state: AgentState,
            at: HarnessTimestamp,
        ) = AgentStatus(identity.agentId, identity.displayName, identity.role.key, state, null, null, at)

        private fun finalState(
            identity: Identity,
            run: RunRecord,
        ): AgentState =
            when {
                identity.status == IdentityStatus.FAILED -> AgentState.FAILED
                run.result == RunResult.RUNNING -> AgentState.IDLE
                else -> AgentState.DONE
            }

        private fun outcomeOf(result: RunResult): RunOutcome? =
            when (result) {
                RunResult.RUNNING -> null
                RunResult.PASSED -> RunOutcome.PASSED
                RunResult.FAILED -> RunOutcome.FAILED
                RunResult.ABORTED -> RunOutcome.ABORTED
            }

        /** Replayed time: wall time, with epoch nanoseconds as the monotonic reading. */
        private fun at(instant: Instant) = HarnessTimestamp(instant, instant.epochSecond * NANOS_PER_SECOND + instant.nano)

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val STEP = 0
        private const val AFTER_STEP = 1
        private const val VERDICT = 2
        private const val JUDGEMENT = 3
    }
}
