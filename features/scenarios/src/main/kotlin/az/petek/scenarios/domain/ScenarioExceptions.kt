package az.petek.scenarios.domain

import az.petek.campaign.domain.ValidationIssue
import az.petek.core.error.PetekException
import az.petek.core.ids.RunId
import java.nio.file.Path

class ScenarioNotFoundException(
    val id: ScenarioVersionId,
) : PetekException("Scenario version $id does not exist")

/** A review action the state machine does not allow (see [ScenarioLifecycle]). */
class ScenarioTransitionException(
    val version: ScenarioVersion,
    val action: String,
    reason: String,
) : PetekException("Cannot $action ${version.label} (${version.status}): $reason")

/** Scenario text that the campaign loader or validator refuses, or that breaks a catalog rule (e.g. a renamed child). */
class ScenarioInvalidException(
    val issues: List<ValidationIssue>,
) : PetekException("Scenario is invalid:\n" + issues.joinToString("\n") { "  - $it" }) {
    init {
        require(issues.isNotEmpty()) { "An invalid scenario has at least one issue" }
    }
}

/** A version is not runnable (a draft or superseded) and the caller did not allow it. */
class ScenarioNotRunnableException(
    val version: ScenarioVersion,
) : PetekException("${version.label} is ${version.status}; only APPROVED or FROZEN versions run by default")

/**
 * A compare-and-set update found the version in another status than expected: someone else changed it meanwhile.
 * Nothing of the batch was applied.
 */
class ConcurrentScenarioChangeException(
    val id: ScenarioVersionId,
) : PetekException("Scenario version $id was changed concurrently; nothing was updated")

/** Attempt to change a FROZEN version (refused by the repository and, for SQLite, by the database itself). */
class FrozenScenarioException(
    val id: ScenarioVersionId,
) : PetekException("Scenario version $id is FROZEN and immutable")

class ScenarioFileException(
    val path: Path,
    reason: String,
    cause: Throwable? = null,
) : PetekException("Scenario file '$path': $reason", cause)

/** Triage needs the scenario text a run executed; the run's campaign hash matches no stored version. */
class ScenarioNotInCatalogException(
    val runId: RunId,
    val campaignHash: String,
) : PetekException(
        "Run $runId used a campaign (sha256 $campaignHash) that is not in the scenario catalog; " +
            "import the campaign file first or name the scenario version explicitly",
    )

class TriageRunNotFoundException(
    val runId: RunId,
) : PetekException("Run $runId does not exist")

/**
 * Triage stores what it collects for good (surprises are write-once, decided ones are not asked again), so it only
 * works on a finished run; a RUNNING run (still going, or its process died) has incomplete evidence and no findings.
 */
class TriageRunNotFinishedException(
    val runId: RunId,
) : PetekException("Run $runId has not finished; triage needs its complete evidence and findings (preview works meanwhile)")
