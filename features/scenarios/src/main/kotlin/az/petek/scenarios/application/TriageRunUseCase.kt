package az.petek.scenarios.application

import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.scenarios.domain.EvidenceRef
import az.petek.scenarios.domain.EvidenceRefType
import az.petek.scenarios.domain.ProposalStatus
import az.petek.scenarios.domain.ProposedChange
import az.petek.scenarios.domain.RunEvidence
import az.petek.scenarios.domain.ScenarioIdGenerator
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioNotInCatalogException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.SecretRedactor
import az.petek.scenarios.domain.Surprise
import az.petek.scenarios.domain.SurpriseCollection
import az.petek.scenarios.domain.SurpriseCollector
import az.petek.scenarios.domain.TextRedactor
import az.petek.scenarios.domain.TriageCategory
import az.petek.scenarios.domain.TriageFailure
import az.petek.scenarios.domain.TriageRepository
import az.petek.scenarios.domain.TriageRunNotFoundException
import az.petek.scenarios.domain.TriageVerdict
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Triage of a finished run (docs/PLAN.md Faza 7, surprise protocol):
 *
 * 1. Finds the scenario version the run executed (by the run's campaign hash, or the version named by the caller)
 *    and collects the run's surprises with [SurpriseCollector]: `report_problem` steps, failed concluding steps and
 *    findings; expected refusals, lost races and environment failures are listed as ignored, never asked about.
 * 2. Asks the LLM ONE structured question per surprise not decided yet (with [TriageOptions.maxQuestions] per
 *    execution), showing the surprise's redacted evidence facts and the scenario YAML. The answer is validated in
 *    code ([TriageAnswerParser]); an invalid answer or an unreachable model is stored as a [TriageFailure] and retried
 *    by the next execution. After [LlmException.Unavailable] no further question is sent.
 * 3. Stores each verdict with the evidence it is based on: the ids the model cited that the question showed, plus
 *    the artifacts of the cited steps; all of the surprise's evidence when it cited none. A proposed change is kept
 *    only for MODEL_GAP / SCENARIO_BUG and only if it applies and passes the campaign validator ([ProposalCheck]);
 *    otherwise it is REJECTED with the reason and the verdict stays.
 * 4. Combines the run's pending proposals, in surprise order, into one v2 DRAFT of the scenario (source TRIAGE,
 *    parent = the executed version) for the owner to review; a proposal that no longer applies on top of the
 *    earlier ones is rejected as conflicting. Identical proposals from several surprises count once.
 *
 * Executing again is safe: surprise ids are derived from the run, decided surprises are not asked again unless
 * `retriage` is set, and pending proposals left by an interrupted execution are drafted by the next one.
 */
class TriageRunUseCase(
    private val llm: LlmClient,
    private val evidence: EvidenceQuery,
    private val runs: RunRepository,
    private val scenarios: ScenarioRepository,
    private val triage: TriageRepository,
    private val validator: ScenarioValidator,
    private val clock: HarnessClock,
    ids: ScenarioIdGenerator,
    private val redactor: TextRedactor = SecretRedactor(),
    private val options: TriageOptions = TriageOptions(),
) {
    private val collector = SurpriseCollector(redactor)
    private val prompt = TriagePrompt(redactor, options)
    private val proposals = ProposalCheck(validator)
    private val drafts = ScenarioDrafts(scenarios, validator, clock, ids)
    private val results = TriageResults(triage)

    /** The surprises and ignored signals of [runId], without asking the model or storing anything. */
    suspend fun preview(
        runId: RunId,
        scenarioId: ScenarioVersionId? = null,
    ): TriagePreview {
        val context = load(runId, scenarioId)
        val collection = collect(context)
        return TriagePreview(context.run, context.version, collection.surprises, collection.ignored)
    }

    /**
     * Triages [runId] against the version the run executed, or [scenarioId] when given. With [retriage], surprises
     * that already have a verdict are asked again (the new verdict replaces the old one).
     * Throws [TriageRunNotFoundException], [ScenarioNotFoundException] or
     * [ScenarioNotInCatalogException] when the run or its scenario cannot be found.
     */
    suspend fun execute(
        runId: RunId,
        scenarioId: ScenarioVersionId? = null,
        retriage: Boolean = false,
    ): TriageReport {
        val context = load(runId, scenarioId)
        val collection = collect(context)
        triage.addSurprises(collection.surprises)
        val decided = triage.verdicts(runId).mapTo(HashSet()) { it.surpriseId }
        val open = collection.surprises.filter { retriage || it.id !in decided }
        val asked = open.take(options.maxQuestions)
        ask(context, asked)
        val draft = compose(context, triage.surprises(runId))
        val report =
            TriageReport(
                run = context.run,
                scenario = context.version,
                items = results.forRun(runId),
                ignored = collection.ignored,
                asked = asked.size,
                deferred = open.size - asked.size,
                draft = draft,
            )
        logger.info {
            "Triage of run $runId (${context.version.label}): ${collection.surprises.size} surprises, " +
                "${collection.ignored.size} ignored, ${asked.size} asked, ${report.deferred} deferred, " +
                "${report.items.count { it.failure != null }} failed" + (draft?.let { ", draft ${it.label}" } ?: "")
        }
        return report
    }

    private suspend fun load(
        runId: RunId,
        scenarioId: ScenarioVersionId?,
    ): TriageContext {
        val run = runs.find(runId) ?: throw TriageRunNotFoundException(runId)
        val version = scenarioId?.let { scenarios.find(it) ?: throw ScenarioNotFoundException(it) } ?: executedVersion(run)
        val check = validator.check(version.yaml, version.fileName)
        val campaign = check.campaign ?: throw ScenarioInvalidException(check.issues)
        val recorded =
            RunEvidence(
                steps = evidence.steps(runId),
                assertions = evidence.assertions(runId),
                findings = evidence.findings(runId),
                artifacts = evidence.artifacts(runId),
            )
        return TriageContext(run, version, campaign, recorded)
    }

    /** The stored version with the run's exact campaign text; the newest one of the run's campaign name wins. */
    private suspend fun executedVersion(run: RunRecord): ScenarioVersion {
        val candidates = scenarios.withHash(run.campaignHash)
        return candidates.lastOrNull { it.name == run.campaignName }
            ?: candidates.lastOrNull()
            ?: throw ScenarioNotInCatalogException(run.runId, run.campaignHash)
    }

    private fun collect(context: TriageContext): SurpriseCollection =
        collector.collect(context.run.runId, context.campaign, context.evidence)

    private suspend fun ask(
        context: TriageContext,
        surprises: List<Surprise>,
    ) {
        if (surprises.isEmpty()) return
        val gate = Semaphore(options.parallelism)
        val unavailable = AtomicReference<String?>(null)
        val artifactsByStep = context.evidence.artifacts.groupBy({ it.stepId }, { it.artifactId.value })
        coroutineScope {
            surprises
                .map { surprise ->
                    async { gate.withPermit { askOne(context, surprise, unavailable, artifactsByStep) } }
                }.awaitAll()
        }
    }

    private suspend fun askOne(
        context: TriageContext,
        surprise: Surprise,
        unavailable: AtomicReference<String?>,
        artifactsByStep: Map<StepId, List<String>>,
    ) {
        unavailable.get()?.let { return fail(surprise, "not asked: $it") }
        val response =
            try {
                llm.complete(prompt.request(context, surprise))
            } catch (e: LlmException.Unavailable) {
                val reason = "LLM unavailable: ${e.message}"
                unavailable.compareAndSet(null, reason)
                return fail(surprise, reason)
            } catch (e: LlmException) {
                return fail(surprise, "LLM call failed (${e::class.simpleName}): ${e.message}")
            }
        when (val parsed = TriageAnswerParser.parse(response.output)) {
            is ParsedAnswer.Invalid -> fail(surprise, "invalid answer: ${parsed.reason}")
            is ParsedAnswer.Valid -> triage.saveVerdict(verdict(context, surprise, parsed.answer, response.model, artifactsByStep))
        }
    }

    private suspend fun fail(
        surprise: Surprise,
        reason: String,
    ) {
        logger.warn { "Triage of ${surprise.id} (${surprise.runId}) failed: $reason" }
        triage.saveFailure(TriageFailure(surprise.id, surprise.runId, redactor.redact(reason), clock.now().wall))
    }

    private suspend fun verdict(
        context: TriageContext,
        surprise: Surprise,
        answer: TriageAnswer,
        model: String,
        artifactsByStep: Map<StepId, List<String>>,
    ): TriageVerdict =
        TriageVerdict(
            surpriseId = surprise.id,
            runId = surprise.runId,
            scenarioVersionId = context.version.id,
            category = answer.category,
            rationale = redactor.redact(answer.rationale),
            confidence = answer.confidence,
            basedOn = basedOn(surprise, answer.evidenceRefs, artifactsByStep),
            proposedChange = answer.proposal?.let { proposal(context, answer.category, it) },
            model = model,
            decidedAt = clock.now().wall,
        )

    /** The cited evidence the question showed, with the artifacts recorded for cited steps; everything if none. */
    private fun basedOn(
        surprise: Surprise,
        cited: List<String>,
        artifactsByStep: Map<StepId, List<String>>,
    ): List<EvidenceRef> {
        val known = surprise.evidence.refs.associateBy { it.id }
        val refs =
            cited
                .mapNotNull {
                    known[
                        it
                            .trim()
                            .removePrefix("[")
                            .removeSuffix("]")
                            .trim(),
                    ]
                }.distinct()
        if (refs.isEmpty()) return surprise.evidence.refs
        val artifacts =
            refs
                .filter { it.type == EvidenceRefType.STEP }
                .flatMap { artifactsByStep[StepId(it.id)].orEmpty() }
                .mapNotNull { known[it] }
        return (refs + artifacts).distinct()
    }

    private suspend fun proposal(
        context: TriageContext,
        category: TriageCategory,
        answer: ProposalAnswer,
    ): ProposedChange =
        when (answer) {
            is ProposalAnswer.Malformed -> {
                ProposedChange("", emptyList(), ProposalStatus.REJECTED, rejection = "malformed proposal: ${answer.reason}")
            }

            is ProposalAnswer.Edits -> {
                val change = ProposedChange(redactor.redact(answer.summary), answer.edits, ProposalStatus.PENDING)
                if (!category.changesScenario) {
                    change.rejected("a $category verdict does not change the scenario")
                } else {
                    when (val outcome = proposals.check(context.version.yaml, answer.edits, context.campaign, context.version.fileName)) {
                        is ProposalCheck.Outcome.Usable -> change
                        is ProposalCheck.Outcome.Unusable -> change.rejected(outcome.reason)
                    }
                }
            }
        }

    /** Drafts the run's pending proposals for this version into one v2 (see the class KDoc, step 4). */
    private suspend fun compose(
        context: TriageContext,
        surprises: List<Surprise>,
    ): ScenarioVersion? {
        val order = surprises.withIndex().associate { (index, surprise) -> surprise.id to index }
        val pending =
            triage
                .verdicts(context.run.runId)
                .filter { it.scenarioVersionId == context.version.id && it.proposedChange?.status == ProposalStatus.PENDING }
                .sortedBy { order[it.surpriseId] ?: Int.MAX_VALUE }
        if (pending.isEmpty()) return null
        var yaml = context.version.yaml
        val merged = mutableListOf<TriageVerdict>()
        val conflicts = mutableListOf<Pair<TriageVerdict, String>>()
        pending.forEach { verdict ->
            val edits = verdict.change.edits
            if (merged.any { it.change.edits == edits }) {
                merged += verdict
                return@forEach
            }
            when (val outcome = proposals.check(yaml, edits, context.campaign, context.version.fileName)) {
                is ProposalCheck.Outcome.Usable -> {
                    yaml = outcome.yaml
                    merged += verdict
                }

                is ProposalCheck.Outcome.Unusable -> {
                    conflicts += verdict to "conflicts with an earlier change of this triage: ${outcome.reason}"
                }
            }
        }
        if (merged.isNotEmpty() && yaml == context.version.yaml) {
            merged.forEach { conflicts += it to "the proposed changes of this triage cancel each other out" }
            merged.clear()
        }
        conflicts.forEach { (verdict, reason) -> triage.saveVerdict(verdict.copy(proposedChange = verdict.change.rejected(reason))) }
        if (merged.isEmpty()) return null
        val draft = drafts.create(yaml, ScenarioSource.TRIAGE, context.version, draftNote(context, merged))
        merged.forEach { triage.saveVerdict(it.copy(proposedChange = it.change.drafted(draft.id))) }
        return draft
    }

    private fun draftNote(
        context: TriageContext,
        merged: List<TriageVerdict>,
    ): String {
        val changes = merged.joinToString("; ") { "${it.surpriseId} ${it.category}: ${it.change.summary.ifBlank { "(no summary)" }}" }
        val note = "Proposed by triage of run ${context.run.runId} on ${context.version.label} (${merged.size} change(s)): $changes"
        return if (note.length <= MAX_NOTE) note else note.take(MAX_NOTE - 1) + "…"
    }

    private val TriageVerdict.change: ProposedChange get() = checkNotNull(proposedChange) { "verdict $surpriseId has no proposal" }

    private companion object {
        const val MAX_NOTE = 2_000
    }
}
