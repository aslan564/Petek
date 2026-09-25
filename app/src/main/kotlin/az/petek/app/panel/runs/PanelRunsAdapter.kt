package az.petek.app.panel.runs

import az.petek.app.campaign.CampaignScaler
import az.petek.app.campaign.IdentitySpecs
import az.petek.app.campaign.ScalingException
import az.petek.app.di.AppContainer
import az.petek.app.panel.Contacts
import az.petek.app.panel.PanelTargets
import az.petek.app.panel.explorer.SetupRun
import az.petek.app.panel.explorer.SetupRuns
import az.petek.app.panel.scenarios.PanelScenariosAdapter
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.core.error.PetekException
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelRuns
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.RunStartView
import az.petek.dashboard.domain.RunSummaryView
import az.petek.dashboard.domain.StabilityView
import az.petek.dashboard.domain.StepStabilityView
import az.petek.dashboard.domain.TriageCategory
import az.petek.dashboard.domain.TriageVerdictView
import az.petek.dashboard.domain.TriageView
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunSummary
import az.petek.reporting.domain.RepeatRunEvidence
import az.petek.reporting.domain.StabilityAnalyzer
import az.petek.scenarios.application.TriageItem
import az.petek.scenarios.domain.EvidenceRefType
import az.petek.scenarios.domain.ProposalStatus
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioNotInCatalogException
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.TriageRunNotFinishedException
import az.petek.scenarios.domain.TriageRunNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Runs, their history, reports, stability and triage for the panel ("Run et", "Hesabatlar", the triage of
 * "Ssenarilər").
 *
 * - **Starting.** A run executes one APPROVED or FROZEN catalog version: its text is exported byte-exact into a
 *   temporary file under the evidence directory and loaded by the same loader as `petek run` (so the run's
 *   `campaign_hash` is the version's SHA-256 and triage finds the version again), then resized to the owner's tester
 *   count ([CampaignScaler.resize]) and validated. The "Hədəf sayt", when it names another address than
 *   `PETEK_TARGET`, runs it there with `PETEK_TARGET` semantics ([RunTargets]). The plan is published to the
 *   orchestrator screen before the first step ([PanelRunWatch]); the run itself goes on in [scope].
 * - **One at a time.** The owner's runs and the explorer's session setup ([runKeepingData]) share one slot; a second
 *   start is a [PanelConflictException]. [cancelRun] cancels the running one; its teardown and report still happen.
 * - **History** comes from the run repository, newest first, with the scenario version each run executed.
 * - **Triage** of a finished run runs in [scope] (a closed browser tab does not stop it) and is resumable.
 *
 * Thread-safe.
 */
internal class PanelRunsAdapter(
    private val container: AppContainer,
    private val scenarios: PanelScenariosAdapter,
    private val targets: RunTargets,
    private val watch: PanelRunWatch,
    private val board: MonitorView,
    private val scope: CoroutineScope,
) : PanelRuns,
    SetupRuns {
    private val lock = Any()
    private var current: Deferred<RunSummary?>? = null
    private val triages = ConcurrentHashMap<RunId, Deferred<List<TriageItem>>>()
    private val triaged: MutableSet<RunId> = ConcurrentHashMap.newKeySet()
    private val evidenceShown = ConcurrentHashMap<ArtifactId, ArtifactRecord>()

    override suspend fun startRun(request: RunRequest): RunStartView {
        val problems = request.problems()
        if (problems.isNotEmpty()) throw PanelRequestException(problems)
        val version = scenarios.runnable(request)
        val target =
            request.target
                ?.takeIf {
                    it.isNotBlank()
                }?.let { PanelTargets.allowed(it, container.config.targetPolicy, PanelInstructions.TARGET) }
        val lease = targets.lease(target)
        val campaign =
            try {
                load(version, lease, request.testers).also {
                    PanelTargets.allowed(it.settings.target.toString(), lease.container.config.targetPolicy, PanelInstructions.TARGET)
                }
            } catch (e: Exception) {
                lease.close()
                throw e
            }
        val (runId, _) = launch(campaign, lease, RunOptions(), request.headful)
        warnAboutUncoveredSteps(campaign, lease.container)
        return RunStartView(runId, version.id.value, campaign.settings.testers)
    }

    override suspend fun cancelRun(): Boolean {
        val running = synchronized(lock) { current?.takeIf { it.isActive } } ?: return false
        running.cancel()
        return true
    }

    override suspend fun runs(): List<RunSummaryView> {
        val versions = scenarios.versionsByHash()
        return container.runs.list(HISTORY_LIMIT).map { summary(it, versions) }
    }

    override suspend fun stability(repeatGroup: String): StabilityView? {
        val runs = container.runs.byRepeatGroup(repeatGroup)
        if (runs.isEmpty()) return null
        val query = container.evidenceQuery
        val rows = StabilityAnalyzer().analyze(runs.map { RepeatRunEvidence(it.runId, query.assertions(it.runId), query.steps(it.runId)) })
        return StabilityView(
            repeatGroup = repeatGroup,
            runs = runs.map { it.runId },
            steps = rows.filter { it.scenarioStep != HARNESS_STEP }.map { StepStabilityView(it.scenarioStep, it.runs, it.passed) },
        )
    }

    override suspend fun triage(runId: RunId): TriageView? {
        val items = container.triageResults.forRun(runId)
        if (items.isEmpty() && runId !in triaged) return null
        return view(runId, items)
    }

    override suspend fun runTriage(runId: RunId): TriageView {
        val run = container.runs.find(runId) ?: throw PanelNotFoundException("Run tapılmadı.")
        if (run.result == RunResult.RUNNING) throw PanelConflictException("Run hələ bitməyib; triaj yalnız bitmiş run üçündür.")
        val job = triages.computeIfAbsent(runId) { scope.async(start = CoroutineStart.LAZY) { triageNow(runId) } }
        job.invokeOnCompletion { triages.remove(runId, job) }
        job.start()
        val items = job.await()
        triaged += runId
        val failed = items.filter { it.verdict == null && it.failure != null }
        if (items.isNotEmpty() && failed.size == items.size) {
            throw PanelUnavailableException("Triaj alınmadı: ${failed.first().failure?.reason.orEmpty()}. Bir az sonra yenidən cəhd edin.")
        }
        return view(runId, items)
    }

    override suspend fun reportDirectory(runId: RunId): Path? =
        withContext(Dispatchers.IO) {
            container.artifacts
                .runDirectory(runId)
                .resolve(REPORT_DIRECTORY)
                .takeIf { it.resolve(REPORT_FILE).exists() }
        }

    /** An artifact a shown triage verdict cites as its evidence, so the panel may serve it; null for anything else. */
    fun evidenceArtifact(artifactId: ArtifactId): ArtifactRecord? = evidenceShown[artifactId]

    // --- the explorer's session setup -----------------------------------------------------------------------------

    override suspend fun runKeepingData(campaign: Campaign): SetupRun? {
        val lease = targets.lease(null)
        val (runId, job) =
            try {
                launch(campaign, lease, RunOptions(keepData = true), headful = false)
            } catch (_: PanelConflictException) {
                return null
            }
        return try {
            SetupRun(runId, job.await()?.outcome)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) return SetupRun(runId, null)
            withContext(NonCancellable) {
                job.cancelAndJoin()
                runCatching { container.teardown.teardown(runId) }.onFailure { logger.warn(it) { "Teardown of run $runId failed" } }
            }
            throw e
        }
    }

    // --- running --------------------------------------------------------------------------------------------------

    /**
     * Starts [campaign] in the run slot and returns its run id once the runner created its record. From the call on,
     * [lease] is this function's: it is closed at once when the slot is taken ([PanelConflictException]), otherwise when
     * the run ends. A run that does not start is a [PanelUnavailableException].
     */
    private suspend fun launch(
        campaign: Campaign,
        lease: RunTarget,
        options: RunOptions,
        headful: Boolean,
    ): Pair<RunId, Deferred<RunSummary?>> {
        val started = CompletableDeferred<RunId>()
        val entered = AtomicBoolean(false)
        val job =
            synchronized(lock) {
                if (current?.isActive == true) {
                    lease.close()
                    throw PanelConflictException("Artıq bir run gedir. Bitməsini gözləyin və ya dayandırın.")
                }
                watch.expect(campaign, started)
                val config = lease.container.config
                val runner = lease.container.campaignRunner(headless = config.browserHeadless && !headful)
                scope
                    .async {
                        entered.set(true)
                        try {
                            runner.run(campaign, options)
                        } catch (e: CancellationException) {
                            logger.info { "A run started from the panel was cancelled" }
                            started.cancel()
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "A run started from the panel failed" }
                            started.completeExceptionally(e)
                            null
                        } finally {
                            withContext(NonCancellable) { lease.close() }
                        }
                    }.also { job ->
                        // A job cancelled before its body ran never reaches the finally above.
                        job.invokeOnCompletion { if (!entered.get()) lease.close() }
                        current = job
                    }
            }
        val runId =
            try {
                withTimeoutOrNull(RUN_START_TIMEOUT) { started.await() }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) null else throw e
            } catch (e: Exception) {
                throw PanelUnavailableException("Run başlamadı: ${e.message ?: e::class.simpleName}")
            }
        if (runId == null) {
            job.cancel()
            throw PanelUnavailableException("Run başlamadı; səbəb loglardadır (evidence/logs/petek.log).")
        }
        return runId to job
    }

    /** Exports [version] byte-exact, loads it like `petek run` and applies the tester count. */
    private suspend fun load(
        version: ScenarioVersion,
        lease: RunTarget,
        testers: Int?,
    ): Campaign {
        val container = lease.container
        val loaded =
            withContext(Dispatchers.IO) {
                val directory = container.config.evidenceDir.resolve(EXPORT_DIRECTORY)
                Files.createDirectories(directory)
                val folder = Files.createTempDirectory(directory, "${version.name.take(NAME_CHARS)}-")
                val file = folder.resolve(version.fileName)
                try {
                    scenarios.export(version, file)
                    container.campaigns.execute(file, container.knownRunFunctions)
                } catch (e: PetekException) {
                    throw PanelRequestException(listOf(FieldProblem(RunRequest.SCENARIO, "Ssenari yüklənmədi: ${e.message}")))
                } finally {
                    Files.deleteIfExists(file)
                    Files.deleteIfExists(folder)
                }
            }
        if (testers == null || testers == loaded.settings.testers) return loaded
        val resized =
            try {
                CampaignScaler.resize(loaded, testers)
            } catch (e: ScalingException) {
                throw PanelRequestException(
                    listOf(FieldProblem(PanelInstructions.TESTERS, "$testers tester bu ssenari üçün uyğun deyil: ${e.message}")),
                )
            }
        val issues = DefaultCampaignValidator(container.templateRenderer).validate(resized, container.knownRunFunctions)
        if (issues.isNotEmpty()) {
            throw PanelRequestException(
                listOf(FieldProblem(PanelInstructions.TESTERS, "$testers tester bu ssenari üçün uyğun deyil: ${issues.first().message}")),
            )
        }
        return resized
    }

    /** Tells the board which steps nobody can run with this tester count (they will be skipped); never blocks. */
    private fun warnAboutUncoveredSteps(
        campaign: Campaign,
        container: AppContainer,
    ) {
        try {
            val identities =
                container.identityGenerator
                    .generate(
                        IdentitySpecs.of(campaign.settings, container.config.mailDomain),
                        RunTags.forPlan(campaign.sourceHash, campaign.settings.seed),
                    ).identities
            val uncovered = CampaignScaler.uncoveredSteps(campaign, identities, DefaultActorResolver())
            if (uncovered.isNotEmpty()) {
                board.message(
                    "Diqqət: ${campaign.settings.testers} testerlə bu addımları icra edən olmayacaq və onlar buraxılacaq: " +
                        uncovered.joinToString { it.id },
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "uncovered steps could not be checked" }
        }
    }

    // --- views ----------------------------------------------------------------------------------------------------

    private suspend fun summary(
        run: RunRecord,
        versions: Map<String, List<ScenarioVersion>>,
    ): RunSummaryView {
        val runId = run.runId
        val query = container.evidenceQuery
        val steps = query.steps(runId).filter { it.kind == StepKind.DO || it.kind == StepKind.RUN }
        val assertions = query.assertions(runId)
        val usage = query.usage(runId)
        return RunSummaryView(
            runId = runId,
            campaignName = run.campaignName,
            target = run.target,
            startedAt = run.startedAt,
            endedAt = run.endedAt,
            durationMs = run.endedAt?.let { it.toEpochMilli() - run.startedAt.toEpochMilli() },
            result = run.result,
            testers = container.identities.findByRun(runId).size,
            stepsPassed = steps.count { it.status == StepStatus.PASSED },
            stepsFailed =
                steps.count {
                    it.status == StepStatus.FAILED || it.status == StepStatus.ERROR || it.status == StepStatus.BLOCKED
                },
            assertionsPassed = assertions.count { it.verdict == Verdict.PASSED },
            assertionsFailed = assertions.count { it.verdict == Verdict.FAILED },
            findings = query.findings(runId).size,
            inputTokens = usage.sumOf { it.inputTokens },
            outputTokens = usage.sumOf { it.outputTokens },
            costUsd = usage.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
            repeatGroup = run.repeatGroup,
            repeatIndex = run.repeatIndex,
            reportAvailable = reportDirectory(runId) != null,
            triaged = runId in triaged || container.triageResults.forRun(runId).any { it.verdict != null },
            scenarioId = executed(run, versions)?.id?.value,
        )
    }

    /** The catalog version a run executed: the one with its exact text, preferably of its campaign's name. */
    private fun executed(
        run: RunRecord,
        versions: Map<String, List<ScenarioVersion>>,
    ): ScenarioVersion? {
        val candidates = versions[run.campaignHash].orEmpty()
        return candidates.lastOrNull { it.name == run.campaignName } ?: candidates.lastOrNull()
    }

    private suspend fun triageNow(runId: RunId): List<TriageItem> =
        try {
            container.triage.execute(runId).items
        } catch (e: ScenarioNotInCatalogException) {
            throw PanelConflictException(
                "Bu run kataloqdakı heç bir ssenari versiyasının mətni ilə getməyib; triaj ssenarinin mətnini istəyir.",
            )
        } catch (e: TriageRunNotFinishedException) {
            throw PanelConflictException("Run hələ bitməyib; triaj yalnız bitmiş run üçündür.")
        } catch (e: TriageRunNotFoundException) {
            throw PanelNotFoundException("Run tapılmadı.")
        } catch (e: ScenarioInvalidException) {
            throw PanelConflictException("Run-ın ssenarisi artıq yoxlamadan keçmir: ${e.issues.firstOrNull()?.message.orEmpty()}")
        }

    private suspend fun view(
        runId: RunId,
        items: List<TriageItem>,
    ): TriageView {
        val decided = items.filter { it.verdict != null }
        val artifacts =
            decided
                .flatMap { item ->
                    item.verdict
                        ?.basedOn
                        .orEmpty()
                        .filter { it.type == EvidenceRefType.ARTIFACT }
                        .map { ArtifactId(it.id) }
                }.toSet()
        if (artifacts.isNotEmpty()) {
            container.evidenceQuery
                .artifacts(runId)
                .filter { it.artifactId in artifacts }
                .forEach { evidenceShown[it.artifactId] = it }
        }
        val scenarioId =
            decided.firstNotNullOfOrNull { it.verdict?.scenarioVersionId?.value }
                ?: container.runs.find(runId)?.let { executed(it, scenarios.versionsByHash())?.id?.value }
        return TriageView(
            runId = runId,
            scenarioId = scenarioId,
            verdicts =
                decided.map { item ->
                    val surprise = item.surprise
                    val verdict = checkNotNull(item.verdict)
                    val change = verdict.proposedChange
                    TriageVerdictView(
                        surpriseId = surprise.id.value,
                        scenarioStep = surprise.scenarioStep,
                        agentId = surprise.agentId,
                        surpriseKind = surprise.kind.name,
                        surprise = withoutContacts(surprise.text),
                        category = TriageCategory.valueOf(verdict.category.name),
                        rationale = withoutContacts(verdict.rationale),
                        confidence = verdict.confidence,
                        proposedChange =
                            change?.let {
                                val summary = it.summary.ifBlank { "dəyişiklik təklifi" }
                                val text =
                                    if (it.status ==
                                        ProposalStatus.REJECTED
                                    ) {
                                        "$summary — istifadə olunmadı: ${it.rejection}"
                                    } else {
                                        summary
                                    }
                                withoutContacts(text)
                            },
                        proposalScenarioId = change?.draftId?.value,
                        evidence =
                            verdict.basedOn
                                .filter { it.type == EvidenceRefType.ARTIFACT }
                                .map { ArtifactId(it.id) }
                                .filter { evidenceShown.containsKey(it) },
                    )
                },
        )
    }

    private fun withoutContacts(text: String): String = Contacts.masked(text)

    private companion object {
        /** The newest runs the history shows; each summary reads its run's evidence, so the list stays bounded. */
        const val HISTORY_LIMIT = 100

        /** The orchestrator files its own housekeeping (browser, network, teardown) under this step; no scenario step. */
        const val HARNESS_STEP = "harness"
        const val EXPORT_DIRECTORY = "panel-runs"
        const val NAME_CHARS = 40
        const val REPORT_DIRECTORY = "report"
        const val REPORT_FILE = "index.html"
        val RUN_START_TIMEOUT = 120.seconds
    }
}
