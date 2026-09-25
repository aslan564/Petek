package az.petek.explorer.domain

import az.petek.core.ids.RunId
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Identifies one exploration (docs/PLAN.md Faza 6). The value is also the evidence directory its screenshots are
 * stored under, so it must be a single safe path segment such as `exp_0199…`.
 */
@JvmInline
value class ExplorationId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "ExplorationId must be a safe name such as exp_1, was '$value'" }
    }

    /** The evidence store files artifacts per run id; an exploration uses its own id as that key. */
    val evidenceKey: RunId get() = RunId(value)

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

        /** Derives an exploration id from a fresh run id, so every id still comes from the injected id generator. */
        fun from(runId: RunId): ExplorationId = ExplorationId("exp_" + runId.value.removePrefix("run_"))
    }
}

/**
 * The three passes of an exploration (docs/PLAN.md Faza 6 "Üç fazalı gəzinti"):
 * [ANONYMOUS] and [ROLE_BASED] only read the site; [TRIAL_TOUCH] is the only pass that submits anything.
 */
enum class ExplorationPhase {
    /** Crawl as a visitor without a session; never submits a form. */
    ANONYMOUS,

    /** Crawl again with each logged-in session the caller passes, to compare what each role can reach and do. */
    ROLE_BASED,

    /** Submit every observed CREATE form once with harmless data; only with `allowWrites` on a confirmed test target. */
    TRIAL_TOUCH,
}

/**
 * Limits of one exploration. [maxPages] bounds every crawl pass separately (the anonymous pass and each role pass);
 * [maxMinutes] bounds the whole exploration, after which the partial model is saved as [ExplorationStatus.TIMED_OUT];
 * [maxDepth] is the number of link hops from the target (the target itself is depth 0).
 */
data class ExplorationBudget(
    val maxPages: Int = 40,
    val maxMinutes: Int = 15,
    val maxDepth: Int = 4,
) {
    init {
        require(maxPages in 1..MAX_PAGES) { "maxPages must be in 1..$MAX_PAGES, was $maxPages" }
        require(maxMinutes in 1..MAX_MINUTES) { "maxMinutes must be in 1..$MAX_MINUTES, was $maxMinutes" }
        require(maxDepth in 0..MAX_DEPTH) { "maxDepth must be in 0..$MAX_DEPTH, was $maxDepth" }
    }

    val timeLimit: Duration get() = maxMinutes.minutes

    companion object {
        const val MAX_PAGES = 1_000
        const val MAX_MINUTES = 240
        const val MAX_DEPTH = 10
    }
}

/**
 * What the owner asks for: explore [target] (same origin only), grounded by plain-language [instructions].
 * [allowWrites] must be set explicitly for [ExplorationPhase.TRIAL_TOUCH]; even then the explorer writes only after
 * the target is confirmed to be a test target.
 */
data class ExplorationRequest(
    val target: URI,
    val instructions: String? = null,
    val budget: ExplorationBudget = ExplorationBudget(),
    val phases: Set<ExplorationPhase> = setOf(ExplorationPhase.ANONYMOUS, ExplorationPhase.ROLE_BASED),
    val allowWrites: Boolean = false,
) {
    init {
        val scheme = target.scheme?.lowercase()
        require(target.isAbsolute && scheme in WEB_SCHEMES && !target.host.isNullOrBlank()) {
            "The exploration target must be an absolute http(s) URL with a host"
        }
        require(target.rawUserInfo == null) { "The exploration target must not contain credentials" }
        require(phases.isNotEmpty()) { "An exploration needs at least one phase" }
        require((instructions?.length ?: 0) <= MAX_INSTRUCTION_CHARS) {
            "Instructions must be at most $MAX_INSTRUCTION_CHARS characters"
        }
    }

    /** The instructions as the explorer uses them: trimmed, or null when blank. */
    val grounding: String? get() = instructions?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_INSTRUCTION_CHARS = 4_000
        private val WEB_SCHEMES = setOf("http", "https")
    }
}

enum class ExplorationStatus {
    RUNNING,

    /** Every requested phase ran (a phase may have been skipped for a stated reason). */
    COMPLETED,

    /** The time budget ended the exploration; the partial model was saved. */
    TIMED_OUT,

    /** The caller cancelled the exploration; the partial model was saved. */
    CANCELLED,

    /** An unexpected error ended the exploration; whatever was learned so far was saved. */
    FAILED,
}

/** Counts shown live while the model grows. */
data class ModelCounts(
    val pages: Int,
    val forms: Int,
    val actions: Int,
    val realtime: Int,
    val unknowns: Int,
    val findings: Int,
)

/** What an exploration did, for the owner and for the panel. [notes] explain skipped phases and degraded modes. */
data class ExplorationSummary(
    val status: ExplorationStatus,
    val counts: ModelCounts,
    val pagesVisitedByRole: Map<String, Int>,
    val llmCalls: Int,
    val llmAnswersRejected: Int,
    val durationMs: Long,
    val pageBudgetReached: Boolean,
    val phasesRun: List<ExplorationPhase>,
    val phasesSkipped: Map<ExplorationPhase, String>,
    val notes: List<String>,
)

/** One exploration as stored: its request, lifecycle and, once finished, its summary and site model version. */
data class ExplorationRecord(
    val id: ExplorationId,
    val request: ExplorationRequest,
    val status: ExplorationStatus,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val summary: ExplorationSummary? = null,
    val modelVersion: Int? = null,
)

/** Everything one exploration produced. */
data class ExplorationResult(
    val record: ExplorationRecord,
    val model: SiteModel,
    val findings: List<ExplorationFinding>,
)
