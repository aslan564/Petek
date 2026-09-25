package az.petek.scenarios.application

import az.petek.core.time.HarnessClock
import az.petek.scenarios.domain.ConcurrentScenarioChangeException
import az.petek.scenarios.domain.FrozenScenarioException
import az.petek.scenarios.domain.ScenarioFiles
import az.petek.scenarios.domain.ScenarioHash
import az.petek.scenarios.domain.ScenarioIdGenerator
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioLifecycle
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioNotRunnableException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.ScenarioVersionUpdate
import az.petek.scenarios.domain.YamlDiff
import java.nio.file.Path

/** Result of [ScenarioCatalog.importFile]: [created] is false when the file's exact text was already stored. */
data class ImportResult(
    val version: ScenarioVersion,
    val created: Boolean,
)

/**
 * The owner's scenario catalog (docs/PLAN.md Faza 7): versioned scenario texts, reviewed by the owner.
 * Used by the CLI and, later, by the web panel.
 *
 * - Every new version (imported file, explorer or user draft, triage proposal) must load and pass the campaign
 *   validator, starts as DRAFT and gets the next version number of its name.
 * - [approve] makes a draft the scenario's approved version and supersedes the previous one; [freeze] locks an
 *   approved version for good (see [ScenarioLifecycle]; a forbidden transition is a
 *   [az.petek.scenarios.domain.ScenarioTransitionException]). Concurrent reviews are serialized by compare-and-set and
 *   retried, so the later approval wins and no name ever has two approved versions.
 * - Only APPROVED and FROZEN versions are runnable unless the caller explicitly allows a draft ([runnable]).
 * - Texts are stored and exported byte-exact, so a run started from an exported file links back to its version
 *   through the campaign hash.
 */
class ScenarioCatalog(
    private val repository: ScenarioRepository,
    private val validator: ScenarioValidator,
    private val files: ScenarioFiles,
    private val clock: HarnessClock,
    ids: ScenarioIdGenerator,
) {
    private val drafts = ScenarioDrafts(repository, validator, clock, ids)

    /**
     * Imports a campaign file as a USER draft of the scenario it names (its parent is the newest stored version of
     * that name). Importing a text that is already stored for that name returns the stored version instead.
     * Throws [ScenarioInvalidException] (with YAML lines) when the file does not pass the campaign validator.
     */
    suspend fun importFile(
        path: Path,
        note: String? = null,
    ): ImportResult {
        val yaml = files.read(path)
        val fileName = path.fileName?.toString() ?: ScenarioDrafts.DEFAULT_FILE_NAME
        val campaign = drafts.check(yaml, fileName)
        val history = repository.history(campaign.settings.name)
        val sha256 = ScenarioHash.of(yaml)
        history.lastOrNull { it.sha256 == sha256 }?.let { return ImportResult(it, created = false) }
        val version = drafts.store(yaml, campaign, ScenarioSource.USER, history.lastOrNull(), note ?: "imported from $fileName")
        return ImportResult(version, created = true)
    }

    /**
     * Stores [yaml] as a new DRAFT, e.g. written by the explorer or by the owner in the panel. With [parentId] the
     * draft is a new version of that scenario and must keep its name. [fileName] names a text without `campaign.name`.
     */
    suspend fun createDraft(
        yaml: String,
        source: ScenarioSource,
        parentId: ScenarioVersionId? = null,
        note: String = "",
        fileName: String? = null,
    ): ScenarioVersion = drafts.create(yaml, source, parentId?.let { get(it) }, note, fileName)

    /**
     * Approves a draft (idempotent for APPROVED and FROZEN versions). The text is validated again, because the
     * harness may have changed since the draft was written (e.g. a run function was renamed).
     */
    suspend fun approve(id: ScenarioVersionId): ScenarioVersion {
        val target = get(id)
        if (target.status == ScenarioStatus.DRAFT) drafts.check(target.yaml, target.fileName)
        return reviewed(id) { current -> ScenarioLifecycle.approve(current, repository.history(current.name), clock.now().wall) }
    }

    /** Freezes an approved version (idempotent for FROZEN). A frozen version never changes again. */
    suspend fun freeze(id: ScenarioVersionId): ScenarioVersion =
        reviewed(id) { target -> ScenarioLifecycle.freeze(target, clock.now().wall) }

    /** Throws [ScenarioNotFoundException] for an unknown id. */
    suspend fun get(id: ScenarioVersionId): ScenarioVersion = repository.find(id) ?: throw ScenarioNotFoundException(id)

    suspend fun find(id: ScenarioVersionId): ScenarioVersion? = repository.find(id)

    /** Stored versions with one of [statuses], ordered by name, then version. */
    suspend fun list(statuses: Set<ScenarioStatus> = ScenarioStatus.entries.toSet()): List<ScenarioVersion> =
        repository.all().filter { it.status in statuses }

    /** Every version of [name], oldest first. */
    suspend fun history(name: String): List<ScenarioVersion> = repository.history(name)

    /** The version [name] runs by default (see [ScenarioLifecycle.current]), or null when none was approved. */
    suspend fun current(name: String): ScenarioVersion? = ScenarioLifecycle.current(repository.history(name))

    /** The version to run; a DRAFT or SUPERSEDED one only with [allowUnreviewed] ([ScenarioNotRunnableException]). */
    suspend fun runnable(
        id: ScenarioVersionId,
        allowUnreviewed: Boolean = false,
    ): ScenarioVersion {
        val version = get(id)
        if (!version.runnable && !allowUnreviewed) throw ScenarioNotRunnableException(version)
        return version
    }

    /** Unified diff from [from] to [to], labelled with the version labels. */
    suspend fun diff(
        from: ScenarioVersionId,
        to: ScenarioVersionId,
        context: Int = YamlDiff.DEFAULT_CONTEXT,
    ): YamlDiff {
        val old = get(from)
        val new = get(to)
        return YamlDiff.of(old.yaml, new.yaml, old.label, new.label, context)
    }

    /** What [id] changed relative to the version it was derived from; null for a version without a parent. */
    suspend fun diffFromParent(
        id: ScenarioVersionId,
        context: Int = YamlDiff.DEFAULT_CONTEXT,
    ): YamlDiff? {
        val version = get(id)
        return version.parentId?.let { diff(it, id, context) }
    }

    /** Writes the version's exact text to [path]; refuses to replace an existing file unless [overwrite]. */
    suspend fun export(
        id: ScenarioVersionId,
        path: Path,
        overwrite: Boolean = false,
    ): ScenarioVersion {
        val version = get(id)
        files.write(path, version.yaml, overwrite)
        return version
    }

    /**
     * Computes and applies a review transition, recomputing it when a concurrent review changed the versions. A
     * [FrozenScenarioException] is such a change too: a transition is never computed on a FROZEN version, so a frozen
     * one in the batch was frozen meanwhile (e.g. the approved version this approval was about to supersede).
     */
    private suspend fun reviewed(
        id: ScenarioVersionId,
        transition: suspend (ScenarioVersion) -> List<ScenarioVersionUpdate>,
    ): ScenarioVersion {
        repeat(REVIEW_ATTEMPTS) { attempt ->
            val target = get(id)
            val updates = transition(target)
            if (updates.isEmpty()) return target
            try {
                repository.update(updates)
                return get(id)
            } catch (e: ConcurrentScenarioChangeException) {
                if (attempt == REVIEW_ATTEMPTS - 1) throw e
            } catch (e: FrozenScenarioException) {
                if (attempt == REVIEW_ATTEMPTS - 1) throw e
            }
        }
        error("unreachable")
    }

    private companion object {
        /**
         * Each lost compare-and-set means another review of the name completed meanwhile, so this many attempts
         * always succeed with up to this many reviews of one scenario racing; the owner reviews one at a time.
         */
        const val REVIEW_ATTEMPTS = 10
    }
}
