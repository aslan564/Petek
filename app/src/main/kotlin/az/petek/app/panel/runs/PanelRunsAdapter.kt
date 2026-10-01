/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.app.panel.runs

import az.petek.app.campaign.CampaignScaler
import az.petek.app.campaign.IdentitySpecs
import az.petek.app.campaign.ScalingException
import az.petek.app.config.MailSource
import az.petek.app.di.AppContainer
import az.petek.app.panel.Contacts
import az.petek.app.panel.PanelTargets
import az.petek.app.panel.explorer.SetupRun
import az.petek.app.panel.explorer.SetupRuns
import az.petek.app.panel.scenarios.PanelScenariosAdapter
import az.petek.app.runs.RunLockBusyException
import az.petek.app.runs.SlowerLines
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.VisitorRun
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.error.PetekException
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.dashboard.domain.BundleEvidenceView
import az.petek.dashboard.domain.ComparisonView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.FindingBundleView
import az.petek.dashboard.domain.FindingView
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
import az.petek.dashboard.domain.TeardownView
import az.petek.dashboard.domain.TriageCategory
import az.petek.dashboard.domain.TriageVerdictView
import az.petek.dashboard.domain.TriageView
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ReleaseNames
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.llm.domain.LlmProviderKey
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunOptions
import az.petek.orchestration.domain.RunSummary
import az.petek.reporting.application.CompareRunsUseCase
import az.petek.reporting.domain.ComparisonRefusedException
import az.petek.reporting.domain.RepeatRunEvidence
import az.petek.reporting.domain.RunNotFoundException
import az.petek.reporting.domain.StabilityAnalyzer
import az.petek.reporting.domain.StepChange
import az.petek.reporting.domain.visual.VisualGate
import az.petek.reporting.infrastructure.LookLines
import az.petek.scenarios.application.TriageItem
import az.petek.scenarios.domain.CodeTriage
import az.petek.scenarios.domain.EvidenceRefType
import az.petek.scenarios.domain.ProposalStatus
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioNotInCatalogException
import az.petek.scenarios.domain.ScenarioSource
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
import java.net.URI
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
 *   count ([CampaignScaler.resize]) and validated. A version runs on its own site (`campaign.target`), or on the
 *   "Hədəf sayt" when one is given, with `PETEK_TARGET` semantics ([RunTargets]); an explorer draft only on the site it
 *   was written for, since its steps open that site's pages. The plan is published to the
 *   orchestrator screen before the first step ([PanelRunWatch]); the run itself goes on in [scope].
 * - **One at a time.** The owner's runs and the explorer's session setup ([runKeepingData]) share one slot, and the
 *   evidence store's [az.petek.app.runs.RunLock] keeps a run of another process (`petek run`) out too; a second
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

    override suspend fun startRun(request: RunRequest): RunStartView = launch(request).view

    /** A run [launch] started: what "Run et" answers, and a way to wait for the run's end. */
    class LaunchedRun internal constructor(
        val view: RunStartView,
        private val job: Deferred<RunSummary?>,
    ) {
        /** Returns once the run ended (torn down and reported), also when it was stopped. */
        suspend fun join() = job.join()

        /**
         * The runner's own summary once the run ended: its outcome and why, by the same tally `petek run` exits by.
         * Null when the run was stopped or broke.
         */
        suspend fun summary(): RunSummary? =
            try {
                job.await()
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) null else throw e
            }

        /** Stops the run as "Dayandır" does: its teardown and report still happen. */
        fun cancel() = job.cancel()
    }

    /** [startRun] for "Test et", which follows the run it started to its end (Faza 25.3). */
    suspend fun launch(request: RunRequest): LaunchedRun {
        val problems = request.problems()
        if (problems.isNotEmpty()) throw PanelRequestException(problems)
        val version = scenarios.runnable(request)
        val asked =
            request.target
                ?.takeIf { it.isNotBlank() }
                ?.let { PanelTargets.allowed(it, container.config.targetPolicy, PanelInstructions.TARGET) }
        // A scenario runs on its own site (its `campaign.target`), never on whatever site the panel was opened for.
        val own = ownTarget(version)
        if (asked != null && own != null && version.source == ScenarioSource.EXPLORER && !PanelTargets.sameSite(asked, own)) {
            throw PanelRequestException(
                listOf(
                    FieldProblem(
                        PanelInstructions.TARGET,
                        "${version.label} ssenarisini kəşfiyyatçı ${own.host} üçün, o saytın səhifələri ilə yazıb; \"Hədəf sayt\" isə " +
                            "${asked.host}-dir. Hədəf saytı $own edin və ya ${asked.host} saytını kəşf edib onun ssenarisini yaradın. " +
                            "Heç bir sayt test edilmədi.",
                    ),
                ),
            )
        }
        val target = asked ?: own?.let { PanelTargets.allowed(it.toString(), container.config.targetPolicy, PanelInstructions.TARGET) }
        val lease = targets.lease(target)
        val cleared =
            try {
                load(version, lease, request.testers).let {
                    PanelTargets.allowed(it.settings.target.toString(), lease.container.config.targetPolicy, PanelInstructions.TARGET)
                    requireOwnSettings(it)
                    PanelTargets.reachable(it.settings.target, lease.container.reachability, PanelInstructions.TARGET)
                    PanelTargets.runnable(it, lease.container.ownership, PanelInstructions.TARGET).also { cleared ->
                        PanelTargets.apiHost(
                            cleared.campaign,
                            lease.container.config.targetPolicy,
                            lease.container.ownership,
                            PanelInstructions.TARGET,
                        )
                    }
                }
            } catch (e: Exception) {
                lease.close()
                throw e
            }
        val campaign = cleared.campaign
        // Told before the run starts: the caller (the page, a host AI over MCP) gets them with the run's id.
        val warnings = warningsFor(campaign, lease.container)
        val release = request.release?.trim()?.takeIf { it.isNotEmpty() }
        val (started, job) = begin(campaign, lease, RunOptions(ownSite = cleared.ownSite, release = release), request.headful)
        // A closed browser tab cancels this request, never the run: it goes on and the board shows it.
        val runId = awaitStart(started, job)
        warnings.forEach { board.message("Diqqət: $it") }
        return LaunchedRun(RunStartView(runId, version.id.value, campaign.settings.testers, warnings), job)
    }

    /** The history row of [runId]; null for an unknown run. */
    suspend fun summary(runId: RunId): RunSummaryView? = container.runs.find(runId)?.let { summary(it, scenarios.versionsByHash()) }

    /** Whether a run is going. */
    fun busy(): Boolean = synchronized(lock) { current?.isActive == true }

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
            steps =
                rows
                    .filter { it.scenarioStep != HARNESS_STEP }
                    .map {
                        StepStabilityView(it.scenarioStep, it.runs, it.passed, it.siteFailures, it.agentFailures, it.environmentFailures)
                    },
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
        if (items.isNotEmpty() && failed.size == items.size) throw PanelUnavailableException(undecided(failed))
        return view(runId, items)
    }

    override suspend fun reportDirectory(runId: RunId): Path? =
        withContext(Dispatchers.IO) {
            container.artifacts
                .runDirectory(runId)
                .resolve(REPORT_DIRECTORY)
                .takeIf { it.resolve(REPORT_FILE).exists() }
        }

    override suspend fun compare(
        runId: RunId,
        baseline: String?,
        visual: String?,
    ): ComparisonView {
        val gate =
            when {
                visual.isNullOrBlank() || visual.equals("report", ignoreCase = true) -> VisualGate.REPORT
                visual.equals("fail", ignoreCase = true) -> VisualGate.FAIL
                else -> throw PanelRequestException(listOf(FieldProblem("visual", "Görünüş qapısı \"report\" və ya \"fail\" olur.")))
            }
        val choice =
            when {
                baseline.isNullOrBlank() ||
                    baseline.equals(
                        ReleaseNames.PREVIOUS,
                        ignoreCase = true,
                    )
                -> CompareRunsUseCase.Baseline.Previous

                ReleaseNames.RUN_ID.matches(baseline) -> CompareRunsUseCase.Baseline.Run(RunId(baseline))

                else -> CompareRunsUseCase.Baseline.Release(baseline)
            }
        val comparison =
            try {
                container.compareRuns.compare(runId, choice, gate).comparison
            } catch (e: RunNotFoundException) {
                throw PanelNotFoundException("Run tapılmadı: ${e.message}")
            } catch (e: ComparisonRefusedException) {
                throw PanelConflictException(refusal(e, baseline))
            }
        return ComparisonView(
            runId = comparison.current.runId,
            release = comparison.current.release,
            baseline = comparison.baseline.runId,
            baselineRelease = comparison.baseline.release,
            scenario = comparison.current.campaignName,
            scenarioChanged = comparison.scenarioChanged,
            regressed = comparison.regressed,
            newFailures = comparison.newFailures.map { it.scenarioStep },
            fixed = comparison.fixed.map { it.scenarioStep },
            stillFailing = comparison.steps.filter { it.change == StepChange.STILL_FAILING }.map { it.scenarioStep },
            slower = SlowerLines.PANEL.of(comparison),
            notComparable = comparison.steps.filter { it.change == StepChange.NOT_COMPARABLE }.map { it.scenarioStep },
            // The page of this very pair: opening it never compares again, or with another baseline.
            pageUrl = "/runs/${runId.value}/report/compare-${comparison.baseline.runId.value}.html",
            looksChanged = LookLines.changed(comparison),
            looksNotComparable = LookLines.notComparable(comparison),
            visualGate = gate.name.lowercase(),
        )
    }

    /** Why two runs are not compared, said to the owner. */
    private fun refusal(
        e: ComparisonRefusedException,
        baseline: String?,
    ): String =
        when (e.reason) {
            ComparisonRefusedException.Reason.NO_BASELINE -> {
                if (baseline.isNullOrBlank() || baseline.equals(ReleaseNames.PREVIOUS, ignoreCase = true)) {
                    "Müqayisə üçün bu ssenarinin əvvəlki, bitmiş run-ı yoxdur."
                } else {
                    "Bu ssenarinin \"$baseline\" versiyasını yoxlayan bitmiş run-ı yoxdur (versiya run başlayanda adlanır)."
                }
            }

            ComparisonRefusedException.Reason.OTHER_SCENARIO -> {
                "Yalnız eyni ssenarinin run-ları müqayisə olunur."
            }

            ComparisonRefusedException.Reason.NOT_FINISHED -> {
                "Run hələ bitməyib; bitəndən sonra müqayisə edin."
            }

            ComparisonRefusedException.Reason.SAME_RUN -> {
                "Run özü ilə müqayisə olunmur; əvvəlki run-ı və ya versiyanı seçin."
            }
        }

    override suspend fun reportPdf(runId: RunId): Path? {
        if (reportDirectory(runId) == null) return null
        return try {
            container.reportPdf.export(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "The report of $runId could not be printed as a PDF" }
            throw PanelUnavailableException("Hesabat PDF kimi çap edilə bilmədi: ${e.message ?: e::class.simpleName}")
        }
    }

    override suspend fun findings(runId: RunId): List<FindingView> {
        val findings = container.evidenceQuery.findings(runId)
        if (findings.isEmpty()) return emptyList()
        // The findings' screenshots and captures become servable, like a triage verdict's evidence.
        val cited = findings.flatMapTo(mutableSetOf()) { it.artifactIds }
        container.evidenceQuery
            .artifacts(runId)
            .filter { it.artifactId in cited }
            .forEach { evidenceShown[it.artifactId] = it }
        return findings.map {
            FindingView(
                it.findingId,
                it.findingClass,
                it.scenarioStep,
                it.agentId,
                it.a,
                it.b,
                it.c,
                it.note,
                it.artifactIds,
                it.evidenceTier,
            )
        }
    }

    override suspend fun findingBundles(
        runId: RunId,
        findingId: String?,
    ): List<FindingBundleView> {
        val bundles =
            try {
                container.findingBundles.bundles(runId, findingId?.let(::FindingId))
            } catch (_: RunNotFoundException) {
                throw PanelNotFoundException("Run tapılmadı.")
            }
        return bundles.map { bundle ->
            val finding = bundle.finding
            FindingBundleView(
                finding =
                    FindingView(
                        finding.findingId,
                        finding.findingClass,
                        finding.scenarioStep,
                        finding.agentId,
                        finding.a,
                        finding.b,
                        finding.c,
                        finding.note,
                        finding.artifactIds,
                        finding.evidenceTier,
                    ),
                target = bundle.target,
                stepAction = bundle.step?.action,
                stepStatus = bundle.step?.status?.name,
                stepDetail = bundle.step?.detail,
                stepStartedAt = bundle.step?.startedAt,
                stepDurationMs = bundle.step?.durationMs,
                correlationId = bundle.step?.correlationId?.value,
                evidence = bundle.evidence.map { BundleEvidenceView(it.artifactId, it.type.name, it.path, it.text) },
                serverLog = bundle.serverLog,
            )
        }
    }

    override suspend fun teardown(runId: RunId): TeardownView {
        val run = container.runs.find(runId) ?: throw PanelNotFoundException("Run tapılmadı.")
        if (run.result == RunResult.RUNNING) throw PanelConflictException("Run hələ bitməyib; teardown yalnız bitmiş run üçündür.")
        val recorded = runCatching { URI(run.target) }.getOrNull()
        if (recorded == null) throw PanelConflictException("Run-ın saytı oxunmur (${run.target}); teardown edilmədi.")
        val own = PanelTargets.sameSite(recorded, container.config.target)
        if (!own && container.config.profileFor(recorded) == null) {
            throw PanelConflictException(
                "Run başqa saytda (${run.target}) aparılıb; teardown yalnız PETEK_TARGET-dəki və ya hədəf profili olan saytın run-ı üçün işləyir.",
            )
        }
        val lease = targets.lease(recorded)
        val result =
            try {
                lease.container.teardown.teardown(runId)
            } finally {
                lease.close()
            }
        return TeardownView(runId, result.removed, result.failures)
    }

    /** An artifact a shown triage verdict or finding cites as its evidence, so the panel may serve it; null for anything else. */
    fun evidenceArtifact(artifactId: ArtifactId): ArtifactRecord? = evidenceShown[artifactId]

    // --- the explorer's session setup -----------------------------------------------------------------------------

    override suspend fun runKeepingData(campaign: Campaign): SetupRun? {
        val lease = targets.lease(null)
        val (started, job) =
            try {
                begin(campaign, lease, RunOptions(keepData = true), headful = false)
            } catch (_: PanelConflictException) {
                return null
            }
        var runId: RunId? = null
        try {
            runId = awaitStart(started, job)
            return SetupRun(runId, job.await()?.outcome)
        } catch (e: CancellationException) {
            // The run itself was stopped (the owner's "Dayandır"): the caller decides what to do with its data.
            if (runId != null && currentCoroutineContext().isActive) return SetupRun(runId, null)
            // The caller was cancelled, also while the run was still starting: nobody else would ever remove the data
            // a keep-data run leaves behind, so stop the run and tear it down here.
            withContext(NonCancellable) {
                job.cancelAndJoin()
                val created = runId ?: if (started.isCompleted) runCatching { started.await() }.getOrNull() else null
                if (created != null) {
                    runCatching { container.teardown.teardown(created) }
                        .onFailure { logger.warn(it) { "Teardown of run $created failed" } }
                }
            }
            throw e
        }
    }

    // --- running --------------------------------------------------------------------------------------------------

    /**
     * Starts [campaign] in the run slot and returns the deferred run id (completed once the runner created its record)
     * with the run's job. From the call on, [lease] is this function's: it is closed at once when the slot is taken
     * ([PanelConflictException]), otherwise when the run ends.
     */
    private fun begin(
        campaign: Campaign,
        lease: RunTarget,
        options: RunOptions,
        headful: Boolean,
    ): Pair<CompletableDeferred<RunId>, Deferred<RunSummary?>> {
        val started = CompletableDeferred<RunId>()
        val entered = AtomicBoolean(false)
        val job =
            synchronized(lock) {
                if (current?.isActive == true) {
                    lease.close()
                    throw PanelConflictException("Artıq bir run gedir. Bitməsini gözləyin və ya dayandırın.")
                }
                // Another process (`petek run`, another panel) may run over the same evidence store too.
                val held =
                    try {
                        container.runLock.acquire("the panel")
                    } catch (e: RunLockBusyException) {
                        lease.close()
                        throw PanelConflictException("Başqa run gedir (${e.holder}). Bitməsini gözləyin.")
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
                            withContext(NonCancellable) {
                                lease.close()
                                held.close()
                            }
                        }
                    }.also { job ->
                        // A job cancelled before its body ran never reaches the finally above.
                        job.invokeOnCompletion {
                            if (!entered.get()) {
                                started.cancel()
                                lease.close()
                                held.close()
                            }
                        }
                        current = job
                    }
            }
        return started to job
    }

    /**
     * The run id of a run [begin] started, once its record exists. A run that does not start is a
     * [PanelUnavailableException] (and is stopped); a cancelled caller gets the [CancellationException] and the run
     * goes on.
     */
    private suspend fun awaitStart(
        started: CompletableDeferred<RunId>,
        job: Deferred<RunSummary?>,
    ): RunId {
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
        return runId
    }

    /**
     * A run that writes (sign-ups, forms, the test API) goes to the configured site or to a site with a target profile
     * (targets/<name>.yaml): only those have their own test token, test API and mail settings. A visitor run only reads,
     * like the explorer, so it may go to any site the owner gave.
     */
    private fun requireOwnSettings(campaign: Campaign) {
        val target = campaign.settings.target
        if (PanelTargets.sameSite(target, container.config.target) || container.config.profileFor(target) != null) return
        if (VisitorRun.findProblems(campaign).isEmpty()) return
        throw PanelRequestException(
            listOf(
                FieldProblem(
                    PanelInstructions.TARGET,
                    "Yazan run (qeydiyyat, formlar, test API) yalnız ${container.config.target} saytında və ya hədəf profili olan " +
                        "saytda işləyir. ${target.host} üçün targets/<ad>.yaml profili yaradın (ünvan, test API, poçt); o vaxta " +
                        "qədər orada yalnız oxuyan ziyarətçi run və \"Kəşf et\" işləyir.",
                ),
            ),
        )
    }

    /** The site [version] names itself (`campaign.target`); null when it names none or cannot be read. */
    private suspend fun ownTarget(version: ScenarioVersion): URI? =
        withContext(Dispatchers.IO) {
            val directory = container.config.evidenceDir.resolve(EXPORT_DIRECTORY)
            Files.createDirectories(directory)
            val folder = Files.createTempDirectory(directory, "target-")
            val file = folder.resolve(version.fileName)
            try {
                scenarios.export(version, file)
                YamlCampaignSource().load(file).settings.target
            } catch (_: PetekException) {
                null
            } finally {
                Files.deleteIfExists(file)
                Files.deleteIfExists(folder)
            }
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

    /**
     * Which steps nobody can run with this tester count (they will be skipped), which receivers a wave leaves without
     * an emitter, which races the waves leave with a single racer (they never pass there: the verdict is inconclusive),
     * and a manual mail source for many testers; never blocks. Empty when they cannot be worked out.
     */
    private fun warningsFor(
        campaign: Campaign,
        container: AppContainer,
    ): List<String> =
        try {
            val identities =
                container.identityGenerator
                    .generate(
                        IdentitySpecs.of(campaign.settings, container.config.mailDomain, container.config.mailInbox),
                        RunTags.forPlan(campaign.sourceHash, campaign.settings.seed),
                    ).identities
            buildList {
                DefaultCampaignValidator(container.templateRenderer).warnings(campaign).forEach { add(it.toString()) }
                if (container.config.mailSource == MailSource.MANUAL && campaign.settings.testers > MANUAL_MAIL_TESTERS) {
                    add(
                        "PETEK_MAIL_SOURCE=manual: ${campaign.settings.testers} testerin hər birinin e-poçt kodunu siz " +
                            "yazırsınız; sürü üçün test poçt qutusu (mailpit, test-api, imap) uyğundur.",
                    )
                }
                val uncovered = CampaignScaler.uncoveredSteps(campaign, identities, DefaultActorResolver())
                if (uncovered.isNotEmpty()) {
                    add(
                        "${campaign.settings.testers} testerlə bu addımları icra edən olmayacaq və onlar buraxılacaq: " +
                            uncovered.joinToString { it.id },
                    )
                }
                CampaignScaler.waitsWithoutEmitter(campaign, identities, DefaultActorResolver()).forEach { gap ->
                    val never = if (gap.covered) "" else " Heç bir dalğada ikisi bir yerdə deyil, ona görə bu addım heç yoxlanmayacaq."
                    add(
                        "dalğa ölçüsü ${campaign.settings.waveSize} olduğu üçün '${gap.step.id}' addımı " +
                            "${gap.waves.joinToString()} nömrəli dalğada '${gap.step.waitFor?.event}' hadisəsini gözləyir, " +
                            "amma orada onu emit edən '${gap.emitter.id}' addımının testeri yoxdur; oradakı qəbul edənlər " +
                            "buraxılacaq.$never",
                    )
                }
                CampaignScaler.racesSplitByWaves(campaign, identities, DefaultActorResolver()).forEach { split ->
                    val where =
                        if (split.waves.size == 1) {
                            "${split.waves.single()} nömrəli dalğada"
                        } else {
                            "${split.waves.joinToString()} nömrəli dalğalarda"
                        }
                    add(
                        "dalğa ölçüsü ${campaign.settings.waveSize} olduğu üçün '${split.step.id}' yarışının $where yalnız bir " +
                            "iştirakçı qalır və yarış orada keçmir; yarış üçün eyni dalğada ən azı 2 iştirakçı lazımdır.",
                    )
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "steps that cannot run could not be checked" }
            emptyList()
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
            assertionsInconclusive = assertions.count { it.verdict == Verdict.INCONCLUSIVE },
            release = run.release,
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
            val passwords = container.identities.findByRun(runId).map { it.password }
            container.triage(passwords).execute(runId).items
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

    /** Why [failed] surprises have no verdict, and what to do, in Azerbaijani. */
    private fun undecided(failed: List<TriageItem>): String {
        val reason =
            failed
                .first()
                .failure
                ?.reason
                .orEmpty()
        val count = "${failed.size} sürpriz"
        return when {
            container.config.llmProvider == LlmProviderKey.NONE -> {
                "$count AI olmadan təsnif oluna bilmir: bunlar agentin özünün bildirdiyi və ya kodun qərar verə bilmədiyi " +
                    "problemlərdir, AI isə qoşulmayıb (PETEK_LLM_PROVIDER=none). .env faylında AI-ı seçin (məsələn " +
                    "PETEK_LLM_PROVIDER=auto, kompüterdəki AI CLI-ni özü tapır) və \"Triaj et\"-i yenidən basın. Sayt " +
                    "yoxlamalarının tapdığı xətaları kod AI-sız təsnif edir."
            }

            "unavailable" in reason.lowercase() -> {
                "$count təsnif olunmadı: AI cavab vermir ($reason). AI-ın işlədiyini yoxlayıb \"Triaj et\"-i yenidən basın."
            }

            else -> {
                "$count təsnif olunmadı: $reason. \"Triaj et\"-i yenidən basın."
            }
        }
    }

    private suspend fun view(
        runId: RunId,
        items: List<TriageItem>,
    ): TriageView {
        val decided = items.filter { it.verdict != null }
        val failed = items.filter { it.verdict == null && it.failure != null }
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
                        rationale =
                            if (verdict.model == CodeTriage.MODEL) {
                                CODE_RATIONALE
                            } else {
                                withoutContacts(verdict.rationale)
                            },
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
            note = failed.takeIf { it.isNotEmpty() }?.let(::undecided),
        )
    }

    private fun withoutContacts(text: String): String = Contacts.masked(text)

    private companion object {
        /** More testers than the explorer's few sessions: typing every code by hand no longer suits. */
        const val MANUAL_MAIL_TESTERS = 3

        /** The newest runs the history shows; each summary reads its run's evidence, so the list stays bounded. */
        const val HISTORY_LIMIT = 100

        /** A verdict code made ([CodeTriage]), as the owner reads it. */
        const val CODE_RATIONALE =
            "Kod qərar verdi, AI yox: brauzerin gördüyünü kod yoxladı və saytda xəta tapdı. Bu, saytın öz xətasıdır, " +
                "bildirilməlidir; ssenari dəyişmir."

        /** The orchestrator files its own housekeeping (browser, network, teardown) under this step; no scenario step. */
        const val HARNESS_STEP = "harness"
        const val EXPORT_DIRECTORY = "panel-runs"
        const val NAME_CHARS = 40
        const val REPORT_DIRECTORY = "report"
        const val REPORT_FILE = "index.html"
        val RUN_START_TIMEOUT = 120.seconds
    }
}
