package az.petek.app.panel

import az.petek.app.campaign.CampaignScaler
import az.petek.app.di.AppContainer
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.domain.LimitingFactor
import az.petek.core.error.PetekException
import az.petek.core.ids.RunId
import az.petek.core.security.TargetVerdict
import az.petek.dashboard.application.UnavailablePanelBackend
import az.petek.dashboard.domain.CapacityLimit
import az.petek.dashboard.domain.CapacityView
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelExplorer
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.RunStartView
import az.petek.dashboard.domain.RunSummaryView
import az.petek.dashboard.domain.ScenarioSource
import az.petek.dashboard.domain.ScenarioStatus
import az.petek.dashboard.domain.ScenarioVersionView
import az.petek.dashboard.domain.ScenarioView
import az.petek.dashboard.domain.StabilityView
import az.petek.dashboard.domain.TriageView
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The web panel's backend in the composition root: capacity advice, the campaign files of `scenarios/` as runnable
 * scenarios, runs started from the page (with the live board fed through the container's decorators), run history
 * and reports. The explorer and the versioned scenario catalog are not connected yet; their screens say so.
 *
 * Runs go through the same [AppContainer] wiring as `petek run`, so the evidence, the report and the teardown are
 * identical. One run at a time; [close] cancels a running one (its teardown and report still happen).
 */
internal class AppPanelBackend(
    private val container: AppContainer,
    private val workingDirectory: Path,
    private val capacityAdvice: RecommendCapacityUseCase,
    private val runStarts: RunStartSignal,
    private val explorer: PanelExplorer = UnavailablePanelBackend(),
) : PanelBackend,
    PanelExplorer by explorer,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var current: Job? = null
    private val scenarioOfRun = ConcurrentHashMap<RunId, String>()

    override suspend fun capacity(testers: Int): CapacityView {
        val advice = capacityAdvice.execute(contextsPerBrowser = BrowserEngineConfig.DEFAULT_CONTEXTS_PER_BROWSER)
        return CapacityView(
            requested = testers,
            recommended = advice.maxTesters,
            limitingFactor = if (advice.limitingFactor == LimitingFactor.CPU) CapacityLimit.CPU else CapacityLimit.MEMORY,
            availableMemoryMb = advice.host.availableMemoryBytes / MIB,
            totalMemoryMb = advice.host.totalMemoryBytes / MIB,
            cpuCores = advice.host.cpuCores,
            measured = advice.perSession.measured,
            notes = advice.notes,
        )
    }

    // --- scenarios: the campaign files of scenarios/ (the versioned catalog is connected later) -----------------

    override suspend fun scenarios(): List<ScenarioVersionView> =
        withContext(Dispatchers.IO) {
            val dir = workingDirectory.resolve(SCENARIO_DIRECTORY)
            if (!dir.isDirectory()) return@withContext emptyList()
            Files.list(dir).use { files ->
                files
                    .filter { it.extension == "yaml" || it.extension == "yml" }
                    .sorted()
                    .map(::fileVersion)
                    .toList()
            }
        }

    override suspend fun scenario(id: String): ScenarioView? =
        withContext(Dispatchers.IO) {
            val file = scenarioFile(id) ?: return@withContext null
            ScenarioView(fileVersion(file), file.readText())
        }

    override suspend fun generateScenario(): ScenarioView = throw notConnected("Ssenari yaratmaq kəşfiyyatçı qoşulanda işləyəcək.")

    override suspend fun diff(
        fromId: String,
        toId: String,
    ): DiffView = throw notConnected("Ssenari versiyalarının müqayisəsi tezliklə qoşulacaq.")

    override suspend fun approve(id: String): ScenarioVersionView =
        throw PanelConflictException("scenarios/ qovluğundakı fayllar artıq təsdiqlənmiş sayılır.")

    override suspend fun freeze(id: String): ScenarioVersionView = throw notConnected("Dondurma ssenari kataloqu qoşulanda işləyəcək.")

    override suspend fun runPlan(scenarioId: String): RunPlanView? = null

    // --- runs ----------------------------------------------------------------------------------------------------

    override suspend fun startRun(request: RunRequest): RunStartView {
        val problems = request.problems()
        if (problems.isNotEmpty()) throw PanelRequestException(problems)
        val file =
            request.campaignPath?.let { workingDirectory.resolve(it).normalize() }
                ?: request.scenarioId?.let(::scenarioFile)
                ?: throw PanelNotFoundException("Ssenari tapılmadı: ${request.scenarioId}")
        val campaign = load(file, request.testers)
        val config = container.config
        val verdict = config.targetPolicy.verify(campaign.settings.target)
        if (verdict is TargetVerdict.Refused) {
            throw PanelRequestException(listOf(FieldProblem("target", "Bu hədəfə icazə yoxdur: ${verdict.reason}")))
        }
        val started = CompletableDeferred<RunId>()
        synchronized(lock) {
            if (current?.isActive == true) throw PanelConflictException("Artıq bir run gedir. Bitməsini gözləyin və ya dayandırın.")
            runStarts.expect(started)
            val runner = container.campaignRunner(headless = config.browserHeadless && !request.headful)
            current =
                scope.launch {
                    try {
                        runner.run(campaign, RunOptions())
                    } catch (e: CancellationException) {
                        logger.info { "Run started from the panel was cancelled" }
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Run started from the panel failed" }
                        started.completeExceptionally(e)
                    }
                }
        }
        val runId =
            withTimeoutOrNull(RUN_START_TIMEOUT) { started.await() }
                ?: throw PanelUnavailableException("Run başlamadı; səbəb loglardadır (evidence/logs/petek.log).")
        request.scenarioId?.let { scenarioOfRun[runId] = it }
        return RunStartView(runId, request.scenarioId, campaign.settings.testers)
    }

    override suspend fun cancelRun(): Boolean {
        val job = synchronized(lock) { current?.takeIf { it.isActive } } ?: return false
        job.cancel()
        return true
    }

    override suspend fun runs(): List<RunSummaryView> {
        val ids = (runStarts.seen() + listOfNotNull(container.runs.latest()?.runId)).distinct()
        return ids.mapNotNull { summary(it) }.sortedByDescending { it.startedAt }
    }

    override suspend fun stability(repeatGroup: String): StabilityView? = null

    override suspend fun triage(runId: RunId): TriageView? = null

    override suspend fun runTriage(runId: RunId): TriageView = throw notConnected("Triaj ssenari kataloqu qoşulanda işləyəcək.")

    override suspend fun reportDirectory(runId: RunId): Path? =
        container.artifacts
            .runDirectory(runId)
            .resolve(REPORT_DIRECTORY)
            .takeIf { it.resolve(REPORT_FILE).exists() }

    override fun close() {
        scope.cancel()
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private suspend fun load(
        file: Path,
        testers: Int?,
    ): Campaign {
        val loaded =
            try {
                withContext(Dispatchers.IO) { container.campaigns.execute(file, container.knownRunFunctions) }
            } catch (e: PetekException) {
                throw PanelRequestException(listOf(FieldProblem(RunRequest.SCENARIO, "Ssenari yüklənmədi: ${e.message}")))
            }
        if (testers == null || testers == loaded.settings.testers) return loaded
        val scaled = CampaignScaler.scale(loaded, testers)
        val issues = DefaultCampaignValidator(container.templateRenderer).validate(scaled, container.knownRunFunctions)
        if (issues.isNotEmpty()) {
            throw PanelRequestException(
                listOf(FieldProblem(PanelInstructions.TESTERS, "$testers tester bu ssenari üçün azdır: ${issues.first().message}")),
            )
        }
        return scaled
    }

    private suspend fun summary(runId: RunId): RunSummaryView? {
        val run = container.runs.find(runId) ?: return null
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
            stepsFailed = steps.count { it.status == StepStatus.FAILED || it.status == StepStatus.ERROR },
            assertionsPassed = assertions.count { it.verdict == Verdict.PASSED },
            assertionsFailed = assertions.count { it.verdict == Verdict.FAILED },
            findings = query.findings(runId).size,
            inputTokens = usage.sumOf { it.inputTokens },
            outputTokens = usage.sumOf { it.outputTokens },
            costUsd = usage.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
            repeatGroup = run.repeatGroup,
            repeatIndex = run.repeatIndex,
            reportAvailable = reportDirectory(runId) != null,
            triaged = false,
            scenarioId = scenarioOfRun[runId],
        )
    }

    private fun fileVersion(file: Path): ScenarioVersionView {
        val modified: Instant = file.getLastModifiedTime().toInstant()
        return ScenarioVersionView(
            id = FILE_PREFIX + workingDirectory.relativize(file).toString(),
            name = file.nameWithoutExtension,
            version = 1,
            status = ScenarioStatus.APPROVED,
            source = ScenarioSource.USER,
            parentId = null,
            note = "Fayl: ${workingDirectory.relativize(file)}",
            createdAt = modified,
            approvedAt = modified,
            frozenAt = null,
        )
    }

    /** Only files inside `scenarios/` are addressable; anything else (e.g. `../`) is not found. */
    private fun scenarioFile(id: String): Path? {
        if (!id.startsWith(FILE_PREFIX)) return null
        val dir = workingDirectory.resolve(SCENARIO_DIRECTORY).normalize()
        val file = workingDirectory.resolve(id.removePrefix(FILE_PREFIX)).normalize()
        return file.takeIf { it.startsWith(dir) && Files.isRegularFile(it) }
    }

    private fun notConnected(message: String) = PanelUnavailableException(message)

    companion object {
        const val SCENARIO_DIRECTORY = "scenarios"
        const val FILE_PREFIX = "file:"
        private const val REPORT_DIRECTORY = "report"
        private const val REPORT_FILE = "index.html"
        private const val MIB = 1024L * 1024L
        private val RUN_START_TIMEOUT = 120.seconds
    }
}

/**
 * Learns the id of a run as soon as the runner stores its record, so the panel can answer "Run et" with the run id
 * while the run goes on in the background. Wraps the container's run repository.
 */
internal class RunStartSignal {
    private val runIds = CopyOnWriteArrayList<RunId>()

    @Volatile
    private var waiting: CompletableDeferred<RunId>? = null

    fun expect(deferred: CompletableDeferred<RunId>) {
        waiting = deferred
    }

    fun seen(): List<RunId> = runIds.toList()

    fun wrap(delegate: RunRepository): RunRepository =
        object : RunRepository by delegate {
            override suspend fun create(run: RunRecord) {
                delegate.create(run)
                runIds += run.runId
                waiting?.complete(run.runId)
            }
        }
}
