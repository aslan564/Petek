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

import az.petek.core.ids.ArtifactId
import java.time.Instant

/*
 * What the panel shows about the explorer agent (docs/PLAN.md Faza 6): the backend maps the explorer's own model into
 * these views, so the panel never depends on the explorer module and the explorer never on the panel.
 */

/** Which resource sets the recommended tester count. */
enum class CapacityLimit { MEMORY, CPU }

/**
 * Advice on how many testers this machine can run at once, for the tester count the owner typed. Advice only: the
 * panel warns when [exceeds], it never blocks.
 */
data class CapacityView(
    val requested: Int,
    val recommended: Int,
    val limitingFactor: CapacityLimit,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val cpuCores: Int,
    /** Whether the per-session cost was measured on this machine or is the documented estimate. */
    val measured: Boolean,
    val notes: List<String>,
) {
    val exceeds: Boolean get() = requested > recommended
}

enum class ExplorationStatus { RUNNING, FINISHED, TIMED_OUT, FAILED, CANCELLED }

/** The explorer's three walks: anonymous, per role (logged in), and one benign submit per create form. */
enum class ExplorationPhase { ANONYMOUS, ROLE_BASED, TRIAL_TOUCH }

enum class PhaseState { PENDING, RUNNING, DONE, SKIPPED }

/** Whether something was seen on the site or concluded from what was seen (e.g. comparing roles). */
enum class Provenance { OBSERVED, INFERRED }

enum class FindingSeverity { LOW, MEDIUM, HIGH }

/**
 * One exploration as the "Kəşfiyyat" screen shows it, live while [status] is RUNNING. Lists are newest first where
 * time matters ([visited], [activity]) and in the explorer's order otherwise.
 */
data class ExplorationView(
    val id: String,
    val target: String,
    /** The owner's instructions, with the answers given to [unknowns] appended. */
    val instructions: String,
    val status: ExplorationStatus,
    val startedAt: Instant,
    val elapsedMs: Long,
    val budget: PanelBudget,
    val phases: List<PhaseProgress>,
    val currentPage: VisitedPageView?,
    val visited: List<VisitedPageView>,
    val model: SiteModelView,
    val findings: List<ExplorationFindingView>,
    val unknowns: List<UnknownView>,
    val ideas: List<TestIdeaView>,
    /** Campaign YAML generated from the model, once there is enough to build one. */
    val draftYaml: String?,
    /** Model version of an earlier exploration of the same target, when there is one to compare with. */
    val previousModelVersion: Int?,
    val activity: List<ExplorationEventView>,
    /** Why the exploration failed or stopped early; null otherwise. */
    val message: String? = null,
)

data class PhaseProgress(
    val phase: ExplorationPhase,
    val state: PhaseState,
    val pagesVisited: Int,
    /** Roles walked in this phase (ROLE_BASED), e.g. `admin`, `employee`. */
    val roles: List<String> = emptyList(),
)

data class VisitedPageView(
    val url: String,
    val title: String,
    /** `anonymous` or the role the page was visited as. */
    val visitedAs: String,
    val httpStatus: Int?,
    val loadMs: Long?,
    val screenshotArtifactId: ArtifactId?,
    val at: Instant,
)

/** The explorer's picture of the site: pages with their forms and actions, and how the site pushes live updates. */
data class SiteModelView(
    val version: Int,
    val pages: List<PageNodeView>,
    val realtime: List<RealtimeView>,
    /** The site's kind in the owner's words, e.g. `Giriş sistemi` (Faza 17); null before any page was seen. */
    val kind: String? = null,
    /** Why the explorer decided [kind]. */
    val kindReason: String? = null,
    /** The gate in the owner's words: sign-up, sign-in, visitors, verification and what blocks it (Faza 17). */
    val gate: List<String> = emptyList(),
)

data class PageNodeView(
    val id: String,
    /** Ids, uuids and numbers generalised to `{id}`, e.g. `/announcements/{id}`. */
    val urlPattern: String,
    val title: String,
    val purpose: String,
    /** `anonymous` and/or role names. */
    val reachableBy: List<String>,
    val provenance: Provenance,
    val forms: List<FormNodeView>,
    val actions: List<ActionNodeView>,
)

data class FormNodeView(
    val purpose: String,
    val provenance: Provenance,
    val fields: List<FieldNodeView>,
)

data class FieldNodeView(
    val label: String,
    val type: String,
    val required: Boolean,
)

data class ActionNodeView(
    val id: String,
    val name: String,
    /** REGISTER, LOGIN, CREATE, UPDATE, DELETE, APPROVE, REJECT, ASSIGN, SUBMIT, NAVIGATE or OTHER. */
    val kind: String,
    val provenance: Provenance,
    val allowedRoles: List<String>,
    /** Roles that must not do it, inferred by comparing what each role saw. */
    val forbiddenRoles: List<String>,
    /** Null while unknown. */
    val triggersRealtime: Boolean?,
)

data class RealtimeView(
    /** `websocket`, `sse`, `polling` or `unknown`. */
    val transport: String,
    val detail: String,
    val pages: List<String>,
)

data class ExplorationFindingView(
    /** BROKEN_LINK, HTTP_ERROR, CONSOLE_ERROR, SLOW_PAGE, ACCESSIBILITY or UNEXPECTED_UI. */
    val kind: String,
    val severity: FindingSeverity,
    val pageUrl: String,
    val detail: String,
    val artifactIds: List<ArtifactId>,
)

/** A question the explorer could not answer from the site; the owner's [answer] joins the instructions. */
data class UnknownView(
    val id: String,
    val question: String,
    val context: String,
    val answer: String?,
)

data class TestIdeaView(
    /** HAPPY_PATH, PERMISSION, RACE, REALTIME, BOUNDARY or IDEMPOTENCY. */
    val pattern: String,
    val action: String,
    val rationale: String,
    /** 1 is the most important. */
    val priority: Int,
)

/** One line of the explorer's activity feed (phase started, page visited, action found, question raised, ...). */
data class ExplorationEventView(
    val at: Instant,
    val kind: String,
    val text: String,
)

enum class ModelChangeKind { ADDED, REMOVED, CHANGED }

/** What changed on the site between two explorations of the same target ("fərq kəşfiyyatı"). */
data class SiteModelDiffView(
    val fromVersion: Int,
    val toVersion: Int,
    val changes: List<ModelChangeView>,
)

data class ModelChangeView(
    val kind: ModelChangeKind,
    /** PAGE, FORM, FIELD or ACTION. */
    val subject: String,
    val name: String,
    val detail: String?,
)
