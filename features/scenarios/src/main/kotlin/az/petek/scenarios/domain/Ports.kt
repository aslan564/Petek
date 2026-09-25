package az.petek.scenarios.domain

import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.ValidationIssue
import az.petek.core.ids.RunId
import java.nio.file.Path
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Stored scenario versions. Implementations must be safe for concurrent callers and uphold, whatever the caller does:
 * - version numbers count per name from 1 without gaps, assigned atomically by [add];
 * - content never changes, and a FROZEN version never changes at all ([FrozenScenarioException]);
 * - at most one APPROVED version per name;
 * - [update] is all-or-nothing: each update applies only while the stored status equals its `before` status,
 *   otherwise nothing of the batch is applied and [ConcurrentScenarioChangeException] is thrown.
 */
interface ScenarioRepository {
    /** Stores [draft] as the next version of its name, status DRAFT, and returns it. Throws for an existing id. */
    suspend fun add(draft: NewScenarioVersion): ScenarioVersion

    suspend fun find(id: ScenarioVersionId): ScenarioVersion?

    /** Every version of [name], oldest first. */
    suspend fun history(name: String): List<ScenarioVersion>

    /** Every version, ordered by name, then version. */
    suspend fun all(): List<ScenarioVersion>

    /** Versions whose YAML has this SHA-256 ([ScenarioVersion.sha256]), oldest first. */
    suspend fun withHash(sha256: String): List<ScenarioVersion>

    suspend fun update(updates: List<ScenarioVersionUpdate>)
}

/**
 * Stored triage results. Surprises are kept in collection order and are write-once per id; a verdict or failure is
 * kept per surprise and replaced by a newer one. Storing a verdict clears the surprise's failure (it succeeded);
 * storing a failure keeps an existing verdict (a failed re-triage does not erase the earlier answer).
 */
interface TriageRepository {
    /** Stores the surprises whose id is not stored yet; stored ones are kept unchanged. */
    suspend fun addSurprises(surprises: List<Surprise>)

    suspend fun surprises(runId: RunId): List<Surprise>

    suspend fun saveVerdict(verdict: TriageVerdict)

    suspend fun verdict(surpriseId: SurpriseId): TriageVerdict?

    suspend fun verdicts(runId: RunId): List<TriageVerdict>

    /** Verdicts whose proposal went into the draft [draftId]: why that draft exists. */
    suspend fun verdictsForDraft(draftId: ScenarioVersionId): List<TriageVerdict>

    suspend fun saveFailure(failure: TriageFailure)

    suspend fun failures(runId: RunId): List<TriageFailure>
}

/**
 * Loads scenario text with the campaign loader and checks it with the campaign validator, exactly as a run would.
 * [fileName] is the name the text is checked under: the loader names a campaign whose YAML has no `campaign.name`
 * after its file. Invalid text is a result, not an exception.
 */
fun interface ScenarioValidator {
    suspend fun check(
        yaml: String,
        fileName: String,
    ): ScenarioCheck
}

/** [campaign] is null when the text does not load at all; [issues] lists every problem found (empty = valid). */
data class ScenarioCheck(
    val campaign: Campaign?,
    val issues: List<ValidationIssue>,
) {
    init {
        require(campaign != null || issues.isNotEmpty()) { "A scenario that does not load has at least one issue" }
    }

    val valid: Boolean get() = campaign != null && issues.isEmpty()

    /** The loaded, valid campaign; throws [ScenarioInvalidException] with the issues otherwise. */
    fun validCampaign(): Campaign = campaign?.takeIf { issues.isEmpty() } ?: throw ScenarioInvalidException(issues)
}

/** Scenario files on disk. Text is UTF-8 and written exactly, so an exported file hashes like its version. */
interface ScenarioFiles {
    /** Throws [ScenarioFileException] when the file is missing, unreadable or not valid UTF-8. */
    suspend fun read(path: Path): String

    /** Creates missing parent directories; refuses an existing file unless [overwrite] ([ScenarioFileException]). */
    suspend fun write(
        path: Path,
        text: String,
        overwrite: Boolean,
    )
}

/** Creates ids for new scenario versions. Injected so tests get a predictable sequence. */
fun interface ScenarioIdGenerator {
    fun versionId(): ScenarioVersionId
}

/** Time-ordered UUIDv7 ids with the `scn_` prefix, like the harness's other ids. */
@OptIn(ExperimentalUuidApi::class)
class UuidV7ScenarioIdGenerator : ScenarioIdGenerator {
    override fun versionId(): ScenarioVersionId = ScenarioVersionId("scn_" + Uuid.generateV7().toHexString())
}
