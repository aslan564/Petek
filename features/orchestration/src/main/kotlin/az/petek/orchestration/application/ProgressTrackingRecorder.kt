package az.petek.orchestration.application

import az.petek.core.ids.AgentId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.UsageRecord

/**
 * Decorator that reports agent progress to the [InactivityWatchdog]: every step, artifact or assertion recorded for
 * an agent counts as a sign of life. The composition root wraps the recorder handed to the agent loop and the run
 * functions with this class (using `watchdog::progress`) so that an agent that keeps producing evidence is never
 * marked `blocked`, while one that stops producing it is.
 *
 * Progress is reported only after the delegate accepted the record. Artifacts carry no agent id; the owner is taken
 * from the artifact path (`<run>/<agent>/...`, see [az.petek.evidence.domain.ArtifactStore]).
 */
class ProgressTrackingRecorder(
    private val delegate: EvidenceRecorder,
    private val onProgress: (AgentId) -> Unit,
) : EvidenceRecorder {
    override suspend fun step(record: StepRecord) {
        delegate.step(record)
        record.agentId?.let(onProgress)
    }

    override suspend fun artifact(record: ArtifactRecord) {
        delegate.artifact(record)
        ownerOf(record)?.let(onProgress)
    }

    override suspend fun event(record: EventRecord) = delegate.event(record)

    override suspend fun receipt(record: EventReceipt) = delegate.receipt(record)

    override suspend fun assertion(record: AssertionRecord) {
        delegate.assertion(record)
        record.agentId?.let(onProgress)
    }

    override suspend fun finding(record: FindingRecord) = delegate.finding(record)

    override suspend fun usage(record: UsageRecord) = delegate.usage(record)

    /** The first path segment that is an agent id, whatever its number of digits (`a07`, `a120`, `a1000`). */
    private fun ownerOf(record: ArtifactRecord): AgentId? =
        record.relativePath
            .split('/', '\\')
            .firstNotNullOfOrNull(AgentId::parseOrNull)
}
