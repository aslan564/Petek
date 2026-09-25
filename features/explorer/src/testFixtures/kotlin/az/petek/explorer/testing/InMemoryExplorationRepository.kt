package az.petek.explorer.testing

import az.petek.core.ids.ArtifactId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationRecord
import az.petek.explorer.domain.ExplorationRepository
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.ScenarioDraft
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.TargetKey
import java.net.URI
import java.time.Instant

/**
 * Thread-safe in-memory [ExplorationRepository] with the same rules as the SQLite one (duplicate ids, model versions
 * and event numbers are refused with [IllegalArgumentException]). The lists are public for assertions.
 */
class InMemoryExplorationRepository : ExplorationRepository {
    private val lock = Any()
    val records = mutableListOf<ExplorationRecord>()
    val models = mutableListOf<SiteModel>()
    val findingList = mutableListOf<Pair<ExplorationId, ExplorationFinding>>()
    val eventList = mutableListOf<ExplorationEvent>()
    val artifactList = mutableListOf<ArtifactRecord>()
    val draftList = mutableListOf<ScenarioDraft>()

    override suspend fun create(record: ExplorationRecord) =
        synchronized(lock) {
            require(records.none { it.id == record.id }) { "Exploration ${record.id} already exists" }
            records += record
        }

    override suspend fun finish(
        id: ExplorationId,
        status: ExplorationStatus,
        endedAt: Instant,
        summary: ExplorationSummary,
        modelVersion: Int?,
    ) = synchronized(lock) {
        val index = records.indexOfFirst { it.id == id }
        require(index >= 0) { "Cannot finish unknown exploration $id" }
        records[index] = records[index].copy(status = status, endedAt = endedAt, summary = summary, modelVersion = modelVersion)
    }

    override suspend fun find(id: ExplorationId): ExplorationRecord? = synchronized(lock) { records.firstOrNull { it.id == id } }

    override suspend fun list(
        target: URI?,
        limit: Int,
    ): List<ExplorationRecord> =
        synchronized(lock) {
            records
                .filter { target == null || TargetKey.of(it.request.target) == TargetKey.of(target) }
                .sortedByDescending { it.startedAt }
                .take(limit)
        }

    override suspend fun saveModel(model: SiteModel) =
        synchronized(lock) {
            val key = TargetKey.of(model.target)
            require(
                models.none { (TargetKey.of(it.target) == key && it.version == model.version) || it.explorationId == model.explorationId },
            ) {
                "Site model v${model.version} of $key or a model of ${model.explorationId} already exists"
            }
            models += model
        }

    override suspend fun latestVersion(target: URI): Int =
        synchronized(lock) { models.filter { TargetKey.of(it.target) == TargetKey.of(target) }.maxOfOrNull { it.version } ?: 0 }

    override suspend fun model(
        target: URI,
        version: Int,
    ): SiteModel? = synchronized(lock) { models.firstOrNull { TargetKey.of(it.target) == TargetKey.of(target) && it.version == version } }

    override suspend fun model(explorationId: ExplorationId): SiteModel? =
        synchronized(lock) {
            models.firstOrNull {
                it.explorationId ==
                    explorationId
            }
        }

    override suspend fun versions(target: URI): List<Int> =
        synchronized(lock) { models.filter { TargetKey.of(it.target) == TargetKey.of(target) }.map { it.version }.sorted() }

    override suspend fun saveFinding(
        explorationId: ExplorationId,
        finding: ExplorationFinding,
    ) = synchronized(lock) {
        require(findingList.none { it.second.id == finding.id }) { "Finding ${finding.id} already exists" }
        findingList += explorationId to finding
    }

    override suspend fun findings(explorationId: ExplorationId): List<ExplorationFinding> =
        synchronized(lock) { findingList.filter { it.first == explorationId }.map { it.second } }

    override suspend fun append(event: ExplorationEvent) =
        synchronized(lock) {
            require(eventList.none { it.explorationId == event.explorationId && it.header.seq == event.header.seq }) {
                "Event ${event.header.seq} of ${event.explorationId} already exists"
            }
            eventList += event
        }

    override suspend fun events(
        explorationId: ExplorationId,
        afterSeq: Long,
    ): List<ExplorationEvent> =
        synchronized(lock) { eventList.filter { it.explorationId == explorationId && it.header.seq > afterSeq }.sortedBy { it.header.seq } }

    override suspend fun lastSeq(explorationId: ExplorationId): Long =
        synchronized(lock) { eventList.filter { it.explorationId == explorationId }.maxOfOrNull { it.header.seq } ?: 0 }

    override suspend fun saveArtifact(record: ArtifactRecord) =
        synchronized(lock) {
            artifactList.removeIf { it.artifactId == record.artifactId }
            artifactList += record
        }

    override suspend fun artifact(id: ArtifactId): ArtifactRecord? = synchronized(lock) { artifactList.firstOrNull { it.artifactId == id } }

    override suspend fun artifacts(explorationId: ExplorationId): List<ArtifactRecord> =
        synchronized(lock) { artifactList.filter { it.runId == explorationId.evidenceKey } }

    override suspend fun saveDraft(draft: ScenarioDraft) =
        synchronized(lock) {
            require(draftList.none { it.id == draft.id }) { "Scenario draft ${draft.id} already exists" }
            draftList += draft
        }

    override suspend fun draft(id: String): ScenarioDraft? = synchronized(lock) { draftList.firstOrNull { it.id == id } }

    override suspend fun drafts(explorationId: ExplorationId): List<ScenarioDraft> =
        synchronized(lock) { draftList.filter { it.explorationId == explorationId } }
}
