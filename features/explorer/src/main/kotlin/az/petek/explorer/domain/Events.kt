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

package az.petek.explorer.domain

import az.petek.core.ids.ArtifactId
import java.net.URI
import java.time.Instant

/** Common part of every [ExplorationEvent]: which exploration, its 1-based position in that exploration, and when. */
data class EventHeader(
    val explorationId: ExplorationId,
    val seq: Long,
    val at: Instant,
)

/**
 * What the live panel shows while the explorer learns a site. Events are numbered per exploration ([EventHeader.seq]
 * starts at 1 and has no gaps), stored in order, and replayable, so a panel that connects late can catch up.
 */
sealed interface ExplorationEvent {
    val header: EventHeader

    val explorationId: ExplorationId get() = header.explorationId

    data class Started(
        override val header: EventHeader,
        val target: URI,
        val phases: List<ExplorationPhase>,
        val instructions: String?,
        val budget: ExplorationBudget,
        val allowWrites: Boolean,
    ) : ExplorationEvent

    /** A phase begins; [roles] are the viewpoints it crawls (`anonymous`, or the role names given). */
    data class PhaseStarted(
        override val header: EventHeader,
        val phase: ExplorationPhase,
        val roles: List<String>,
    ) : ExplorationEvent

    /** A requested phase did not run, and why (no sessions, writes not allowed, target not confirmed as test data). */
    data class PhaseSkipped(
        override val header: EventHeader,
        val phase: ExplorationPhase,
        val reason: String,
    ) : ExplorationEvent

    /** A page was loaded. [url] has no query string; [status] is the HTTP status the page answered, when known. */
    data class PageVisited(
        override val header: EventHeader,
        val role: String,
        val url: String,
        val urlPattern: String,
        val title: String,
        val status: Int?,
        val loadMs: Long?,
        val screenshotArtifactId: ArtifactId?,
    ) : ExplorationEvent

    data class ActionDiscovered(
        override val header: EventHeader,
        val action: ActionModel,
    ) : ExplorationEvent

    data class FindingRecorded(
        override val header: EventHeader,
        val finding: ExplorationFinding,
    ) : ExplorationEvent

    data class UnknownRaised(
        override val header: EventHeader,
        val unknown: Unknown,
    ) : ExplorationEvent

    data class ModelUpdated(
        override val header: EventHeader,
        val counts: ModelCounts,
    ) : ExplorationEvent

    /** A scenario draft was generated from this exploration's model (see [ScenarioDraft]). */
    data class DraftReady(
        override val header: EventHeader,
        val draftId: String,
        val name: String,
        val covered: Int,
        val skipped: Int,
    ) : ExplorationEvent

    /** The exploration ended normally, by its time budget or by cancellation; [modelVersion] is the saved model. */
    data class Finished(
        override val header: EventHeader,
        val summary: ExplorationSummary,
        val modelVersion: Int?,
    ) : ExplorationEvent

    /** The exploration ended with an unexpected error; what was learned before it is still saved. */
    data class Failed(
        override val header: EventHeader,
        val reason: String,
        val modelVersion: Int?,
    ) : ExplorationEvent
}

/**
 * Non-blocking sink for [ExplorationEvent]s (the live panel). Called on the exploring coroutine right after the event
 * was stored, so an implementation must return quickly (hand the event to a buffer or flow). An exception thrown here
 * is logged and never stops the exploration.
 */
fun interface ExplorationObserver {
    fun onEvent(event: ExplorationEvent)

    companion object {
        val NONE: ExplorationObserver = ExplorationObserver { }
    }
}
