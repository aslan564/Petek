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
import az.petek.core.ids.EventId
import az.petek.core.ids.RunId
import az.petek.core.model.Role
import java.time.Instant

/*
 * The orchestrator's view of a run for the "Orkestrator" screen: the plan (which agents do what, in which order, who
 * emits and who waits), the state of every task (step × agent), and the events with their receivers.
 */

/** The ordered plan of a run: setup steps first, then the scenario steps. */
data class RunPlanView(
    /** Null for a preview of a scenario that is not running. */
    val runId: RunId?,
    val campaignName: String,
    val steps: List<PlanStepView>,
)

data class PlanStepView(
    /** Scenario step id, e.g. `announce`. */
    val id: String,
    /** A setup flow (registration, login) rather than a scenario step. */
    val setup: Boolean,
    /** The actor expression as written in the campaign, e.g. `role:employee`. */
    val actors: String,
    /** The agents the expression resolved to, in agent order. */
    val agentIds: List<AgentId>,
    /** `do` (natural language, the LLM acts) or `run` (a deterministic function). */
    val kind: String,
    /** The `do` text or the `run` function with its arguments. */
    val action: String,
    val emits: String?,
    val waitFor: String?,
    /** All actors start at the same instant (race tests). */
    val parallel: Boolean,
    /** Assertions as one line each, e.g. `visible_text 'Yeni elan' within 10s`. */
    val assertions: List<String>,
)

/** PENDING until the agent starts the step; WAITING_EVENT while it waits for `wait_for`; LOST_RACE for the losers. */
enum class TaskState { PENDING, WAITING_EVENT, RUNNING, PASSED, FAILED, BLOCKED, SKIPPED, LOST_RACE }

/** One cell of the task matrix: [agentId] doing plan step [stepId] of [runId]. */
data class TaskStateView(
    val runId: RunId,
    val stepId: String,
    val agentId: AgentId,
    val state: TaskState,
    /** Why it failed, was blocked or skipped; what it waits for. */
    val detail: String? = null,
    /** When the harness saw this state; null lets the dashboard stamp it. */
    val updatedAt: Instant? = null,
)

/** An event published by an actor, with every receipt recorded so far (who saw it, after how long, who did not). */
data class EventView(
    val eventId: EventId,
    val name: String,
    val emitter: AgentId,
    val objectId: String?,
    val t0: Instant,
    val receipts: List<ReceiptView>,
) {
    val received: Int get() = receipts.count { it.received }
    val missing: Int get() = receipts.count { !it.received }

    /** Median latency of the receivers that saw the event (the lower middle for an even count). */
    val medianLatencyMs: Long? get() = latencies().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }

    val maxLatencyMs: Long? get() = latencies().lastOrNull()

    private fun latencies(): List<Long> = receipts.filter { it.received }.mapNotNull { it.latencyMs }.sorted()
}

data class ReceiptView(
    val receiver: AgentId,
    val received: Boolean,
    val latencyMs: Long?,
)

/** An agent as the task matrix labels it. */
data class AgentRef(
    val agentId: AgentId,
    val displayName: String,
    val role: Role?,
)

/** Everything the "Orkestrator" screen shows at one instant. */
data class OrchestratorSnapshot(
    val version: Long,
    val generatedAt: Instant,
    val run: RunView,
    val plan: RunPlanView?,
    val agents: List<AgentRef>,
    /** Every reported task of the plan's run, in plan order and then agent order. */
    val tasks: List<TaskStateView>,
    /** Every cell of the plan counted once; cells nobody reported yet are PENDING. */
    val taskCounts: Map<TaskState, Int>,
    /** Newest first, at most [DashboardState.EVENTS_LIMIT]. */
    val events: List<EventView>,
)
