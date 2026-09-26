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

package az.petek.dashboard.testing

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.AgentProfile
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Small builders for evidence and monitor records, with defaults that read well in tests. */
object Records {
    val T0: Instant = Instant.parse("2026-01-01T10:00:00Z")
    val RUN = RunId("run_1")
    val OTHER_RUN = RunId("run_2")

    private val counter = AtomicInteger()

    private fun next(prefix: String) = "${prefix}_${counter.incrementAndGet()}"

    fun a(index: Int) = AgentId.of(index)

    fun at(
        seconds: Long = 0,
        millis: Long = 0,
    ): HarnessTimestamp {
        val wall = T0.plusSeconds(seconds).plusMillis(millis)
        return HarnessTimestamp(wall, (seconds * 1_000 + millis) * 1_000_000)
    }

    fun runRecord(
        runId: RunId = RUN,
        name: String = "Portal elan axını",
        target: String = "https://staging.portal.test",
        startedAt: Instant = T0,
        endedAt: Instant? = null,
        result: RunResult = RunResult.RUNNING,
    ) = RunRecord(
        runId = runId,
        runTag = RunTag("k7x2"),
        campaignHash = "hash",
        campaignName = name,
        seed = 42,
        target = target,
        startedAt = startedAt,
        endedAt = endedAt,
        result = result,
    )

    fun status(
        agent: Int,
        state: AgentState = AgentState.IDLE,
        step: String? = null,
        lastAction: String? = null,
        name: String = "Agent $agent",
        role: Role = Role.EMPLOYEE,
        at: HarnessTimestamp = at(),
    ) = AgentStatus(a(agent), name, role.key, state, step, lastAction, at)

    fun profile(
        agent: Int,
        name: String = "Agent $agent",
        role: Role = Role.EMPLOYEE,
        department: String? = "Satış",
        registration: RegistrationMode = RegistrationMode.INVITE,
    ) = AgentProfile(a(agent), name, role, department, registration)

    fun identity(
        agent: Int,
        name: String = "Agent $agent",
        role: Role = if (agent == 1) Role.ADMIN else Role.EMPLOYEE,
        department: String? = if (agent == 1) null else "Satış",
        registration: RegistrationMode = if (agent == 1) RegistrationMode.OWNER else RegistrationMode.INVITE,
        status: IdentityStatus = IdentityStatus.ACTIVE,
    ) = Identity(
        agentId = a(agent),
        displayName = name,
        email = "agent$agent.k7x2.${a(agent)}@test.portal.test",
        password = Secret("TopSecret-$agent!"),
        phone = "+99450000000$agent",
        role = role,
        department = department,
        registration = registration,
        status = status,
    )

    fun step(
        agent: Int?,
        action: String = "click [12] \"Elan yarat\"",
        status: StepStatus = StepStatus.PASSED,
        kind: StepKind = StepKind.DO,
        scenarioStep: String = "announce",
        detail: String? = null,
        reason: String? = null,
        runId: RunId = RUN,
        startedAt: Instant = T0,
        endedAt: Instant = startedAt.plusMillis(400),
        stepId: StepId = StepId(next("stp")),
    ) = StepRecord(
        stepId = stepId,
        runId = runId,
        agentId = agent?.let(::a),
        scenarioStep = scenarioStep,
        kind = kind,
        action = action,
        llmReason = reason,
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = endedAt.toEpochMilli() - startedAt.toEpochMilli(),
        status = status,
        detail = detail,
        correlationId = CorrelationId(next("cor")),
    )

    fun artifact(
        owner: String,
        type: ArtifactType = ArtifactType.SCREENSHOT,
        runId: RunId = RUN,
        stepId: StepId = StepId(next("stp")),
        id: ArtifactId = ArtifactId(next("art")),
        file: String = "0001-${type.name.lowercase()}.${type.extension}",
    ) = ArtifactRecord(id, runId, stepId, type, "${runId.value}/$owner/$file", "sha-${id.value}", 10)

    fun event(
        emitter: Int,
        name: String = "announcement_created",
        objectId: String? = "42",
        runId: RunId = RUN,
        t0: Instant = T0,
        id: EventId = EventId(next("evt")),
    ) = EventRecord(id, runId, name, a(emitter), objectId, "url_regex", "{}", t0)

    fun receipt(
        event: EventRecord,
        receiver: Int,
        received: Boolean = true,
        latencyMs: Long? = if (received) 830 else null,
        runId: RunId = event.runId,
    ) = EventReceipt(
        eventId = event.eventId,
        runId = runId,
        receiver = a(receiver),
        received = received,
        t1 = if (received) event.t0.plusMillis(latencyMs ?: 0) else null,
        latencyMs = latencyMs,
    )

    fun assertion(
        agent: Int?,
        verdict: Verdict = Verdict.PASSED,
        type: String = "visible_text",
        expected: String = "visible_text 'Yeni elan' within 10s",
        observed: String? = null,
        note: String? = null,
        runId: RunId = RUN,
        stepId: StepId = StepId(next("stp")),
        scenarioStep: String = "receive",
    ) = AssertionRecord(
        stepId = stepId,
        runId = runId,
        agentId = agent?.let(::a),
        scenarioStep = scenarioStep,
        type = type,
        source = EvidenceSource.RECEIVER,
        expected = expected,
        observed = observed,
        verdict = verdict,
        latencyMs = null,
        note = note,
        artifactIds = emptyList(),
    )

    fun finding(
        agent: Int?,
        findingClass: FindingClass = FindingClass.DELIVERY_UI,
        note: String = "a05 elanı 10 s ərzində görmədi",
        runId: RunId = RUN,
        id: FindingId = FindingId(next("fnd")),
        artifacts: List<ArtifactId> = emptyList(),
    ) = FindingRecord(
        findingId = id,
        runId = runId,
        stepId = null,
        scenarioStep = "receive",
        agentId = agent?.let(::a),
        findingClass = findingClass,
        a = "a02 elanı yaratdı",
        b = "a05: görünmədi",
        c = "oracle: elan mövcuddur",
        note = note,
        artifactIds = artifacts,
    )
}
