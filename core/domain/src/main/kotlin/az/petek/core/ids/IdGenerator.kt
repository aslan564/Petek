package az.petek.core.ids

import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Creates unique ids. Injected everywhere so tests can use a deterministic sequence. */
interface IdGenerator {
    fun runId(): RunId

    fun stepId(): StepId

    fun eventId(): EventId

    fun correlationId(): CorrelationId

    fun artifactId(): ArtifactId

    fun findingId(): FindingId
}

/** Time-ordered UUIDv7 ids with a readable type prefix (`run_…`, `stp_…`). */
@OptIn(ExperimentalUuidApi::class)
class UuidV7IdGenerator : IdGenerator {
    private fun next(prefix: String): String = prefix + "_" + Uuid.generateV7().toHexString()

    override fun runId(): RunId = RunId(next("run"))

    override fun stepId(): StepId = StepId(next("stp"))

    override fun eventId(): EventId = EventId(next("evt"))

    override fun correlationId(): CorrelationId = CorrelationId(next("cor"))

    override fun artifactId(): ArtifactId = ArtifactId(next("art"))

    override fun findingId(): FindingId = FindingId(next("fnd"))
}

/** Derives [RunTag]s. A tag is a pure function of its input, so the same input always yields the same tag. */
object RunTags {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun forRun(runId: RunId): RunTag = fromBytes(sha256(runId.value))

    /** Tag used by `petek plan` so repeated planning of the same campaign prints identical identities. */
    fun forPlan(
        campaignHash: String,
        seed: Long,
    ): RunTag = fromBytes(sha256("plan:$campaignHash:$seed"))

    private fun fromBytes(bytes: ByteArray): RunTag =
        RunTag(
            (0 until 4).map { ALPHABET[(bytes[it].toInt() and 0xff) % ALPHABET.length] }.joinToString(""),
        )

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
}
