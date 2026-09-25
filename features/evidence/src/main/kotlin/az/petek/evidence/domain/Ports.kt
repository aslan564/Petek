package az.petek.evidence.domain

import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import java.nio.file.Path
import java.time.Instant

/** Write side of the evidence store. Implementations must be safe to call from many agents concurrently. */
interface EvidenceRecorder {
    suspend fun step(record: StepRecord)

    suspend fun artifact(record: ArtifactRecord)

    suspend fun event(record: EventRecord)

    suspend fun receipt(record: EventReceipt)

    suspend fun assertion(record: AssertionRecord)

    suspend fun finding(record: FindingRecord)

    suspend fun usage(record: UsageRecord)
}

/** Read side, used by the judge and the report. Results are ordered by time. */
interface EvidenceQuery {
    suspend fun steps(runId: RunId): List<StepRecord>

    suspend fun artifacts(runId: RunId): List<ArtifactRecord>

    suspend fun events(runId: RunId): List<EventRecord>

    suspend fun receipts(runId: RunId): List<EventReceipt>

    suspend fun assertions(runId: RunId): List<AssertionRecord>

    suspend fun findings(runId: RunId): List<FindingRecord>

    suspend fun usage(runId: RunId): List<UsageRecord>
}

interface RunRepository {
    suspend fun create(run: RunRecord)

    suspend fun finish(
        runId: RunId,
        result: RunResult,
        endedAt: Instant,
    )

    suspend fun find(runId: RunId): RunRecord?

    suspend fun latest(): RunRecord?

    suspend fun byRepeatGroup(group: String): List<RunRecord>

    suspend fun addResource(resource: RunResource)

    suspend fun resources(runId: RunId): List<RunResource>

    suspend fun removeResource(
        runId: RunId,
        kind: String,
        externalId: String,
    )
}

/** Stores evidence files under `<root>/<runId>/...` and records their hash. */
interface ArtifactStore {
    suspend fun write(
        runId: RunId,
        stepId: StepId,
        owner: String,
        type: ArtifactType,
        bytes: ByteArray,
    ): ArtifactRecord

    fun resolve(record: ArtifactRecord): Path

    fun runDirectory(runId: RunId): Path
}
