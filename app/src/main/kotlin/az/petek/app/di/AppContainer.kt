package az.petek.app.di

import az.petek.agent.application.DefaultAgentLoop
import az.petek.agent.application.DefaultTesterAgentFactory
import az.petek.agent.application.InMemorySharedRunState
import az.petek.agent.application.PlaceholderResolver
import az.petek.agent.application.PromptBuilder
import az.petek.agent.application.RunFunctionRegistry
import az.petek.agent.application.TesterAgentFactory
import az.petek.agent.application.runs.RunFunctions
import az.petek.agent.domain.ConsecutiveLoopDetector
import az.petek.agent.domain.JsonDecisionProtocol
import az.petek.app.config.PetekConfig
import az.petek.app.logging.MdcDiagnosticContext
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.infrastructure.PlaywrightBrowserEngine
import az.petek.campaign.application.LoadCampaignUseCase
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.DefaultTemplateRenderer
import az.petek.campaign.domain.TemplateRenderer
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.UuidV7IdGenerator
import az.petek.core.security.Secret
import az.petek.core.security.TargetPolicy
import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.time.HarnessClock
import az.petek.core.time.SystemHarnessClock
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.EvidenceQuery
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.RunRepository
import az.petek.evidence.infrastructure.FileSystemArtifactStore
import az.petek.evidence.infrastructure.SqliteEvidenceStore
import az.petek.explorer.application.CompareExplorationsUseCase
import az.petek.explorer.application.GenerateScenarioUseCase
import az.petek.explorer.application.ScenarioSettings
import az.petek.explorer.domain.ExplorationRepository
import az.petek.explorer.infrastructure.SqliteExplorationRepository
import az.petek.identity.application.PlanIdentitiesUseCase
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.identity.domain.IdentityRegistryGenerator
import az.petek.identity.domain.IdentityRepository
import az.petek.identity.infrastructure.SqliteIdentityRepository
import az.petek.llm.application.ConcurrencyLimitedLlmClient
import az.petek.llm.application.MeteredLlmClient
import az.petek.llm.application.RetryingLlmClient
import az.petek.llm.application.UsageMeter
import az.petek.llm.domain.LlmClient
import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.application.DefaultAwaitVerificationUseCase
import az.petek.mail.domain.DefaultVerificationExtractor
import az.petek.mail.domain.Mailbox
import az.petek.mail.infrastructure.MailpitMailbox
import az.petek.oracle.domain.DefaultJsonFieldSelector
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.infrastructure.HttpTargetOracle
import az.petek.orchestration.application.CampaignRunner
import az.petek.orchestration.application.DefaultCampaignRunner
import az.petek.orchestration.application.DefaultRepeatRunner
import az.petek.orchestration.application.InactivityWatchdog
import az.petek.orchestration.application.OracleTeardownUseCase
import az.petek.orchestration.application.ProgressTrackingRecorder
import az.petek.orchestration.application.RepeatRunner
import az.petek.orchestration.application.RunFinalizer
import az.petek.orchestration.application.RunnerSettings
import az.petek.orchestration.application.TeardownUseCase
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.infrastructure.CompositeMonitorView
import az.petek.orchestration.infrastructure.LoggingMonitorView
import az.petek.orchestration.infrastructure.MordantMonitorView
import az.petek.reporting.application.BuildReportUseCase
import az.petek.reporting.application.FinalizeRunUseCase
import az.petek.reporting.domain.ThreeSourceJudge
import az.petek.reporting.infrastructure.HtmlReportWriter
import az.petek.reporting.infrastructure.MarkdownReportWriter
import az.petek.scenarios.application.ScenarioCatalog
import az.petek.scenarios.application.TriageResults
import az.petek.scenarios.application.TriageRunUseCase
import az.petek.scenarios.domain.ScenarioIdGenerator
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.SecretRedactor
import az.petek.scenarios.domain.TriageRepository
import az.petek.scenarios.domain.UuidV7ScenarioIdGenerator
import az.petek.scenarios.infrastructure.CampaignScenarioValidator
import az.petek.scenarios.infrastructure.FileSystemScenarioFiles
import az.petek.scenarios.infrastructure.SqliteScenarioRepository
import az.petek.scenarios.infrastructure.SqliteTriageRepository
import az.petek.verification.application.RecordingVerifyStepUseCase
import az.petek.verification.application.VerifyStepUseCase
import az.petek.verification.domain.DefaultAssertionEvaluator
import com.github.ajalt.mordant.terminal.Terminal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * The composition root: builds Pətək's object graph from a [PetekConfig] by hand (constructor injection, no DI
 * framework). Commands take what they need; every expensive or stateful part (database, HTTP clients, browser engine,
 * LLM provider, live board) is created on first use, so `petek plan` never starts a browser and `petek doctor` never
 * creates a database.
 *
 * Wiring decisions:
 * - one SQLite database and one [SqliteEvidenceStore] (recorder + query + runs) per process; the explorer's
 *   repository, the scenario catalog and triage live in the same database (a container built with
 *   [AppOverrides.database] shares its caller's instead of opening its own);
 * - explorations get their own browser engine ([explorerBrowserEngine]), so a run stopping its browsers never closes
 *   an exploration's pages;
 * - the LLM is `Metered(Retrying(ConcurrencyLimited(provider, PETEK_LLM_CONCURRENCY)))`: a permit covers one attempt,
 *   and metering counts one call per agent decision; the [usageMeter] is flushed into the evidence store per run by
 *   the [finalizer] ([UsageFlushingFinalizer]) before the report is written;
 * - the recorder handed to the agent loop and the run functions reports progress to the runner's watchdog;
 * - log lines of a run carry `run_id`/`agent_id` ([MdcDiagnosticContext]);
 * - the monitor is the live Mordant board on an interactive terminal (plus a log-file copy), else log lines.
 *
 * Not thread-safe for [close] racing with use; everything else may be used from any coroutine. [close] releases
 * what was created (monitor, browser engine, HTTP clients, LLM client, database), in reverse order of creation.
 */
class AppContainer(
    val config: PetekConfig,
    private val overrides: AppOverrides = AppOverrides(),
) : AutoCloseable {
    private val resources = Resources()

    val clock: HarnessClock = overrides.clock ?: SystemHarnessClock()
    val ids: IdGenerator = UuidV7IdGenerator()
    val targetPolicy: TargetPolicy get() = config.targetPolicy
    val templateRenderer: TemplateRenderer = DefaultTemplateRenderer()
    val fieldSelector: JsonFieldSelector = DefaultJsonFieldSelector()

    // --- storage ------------------------------------------------------------------------------------------------

    val database: SqliteDatabase by lazy { overrides.database ?: resources.track(SqliteDatabase.open(config.dbPath)) }

    private val evidenceStore: SqliteEvidenceStore by lazy { SqliteEvidenceStore(database) }

    val runs: RunRepository by lazy { overrides.runsDecorator?.invoke(evidenceStore) ?: evidenceStore }
    val evidenceQuery: EvidenceQuery get() = evidenceStore
    val recorder: EvidenceRecorder by lazy { overrides.recorderDecorator?.invoke(evidenceStore) ?: evidenceStore }

    val artifacts: ArtifactStore by lazy { FileSystemArtifactStore(config.evidenceDir, ids) }

    // --- campaign and identities --------------------------------------------------------------------------------

    /** Loads and validates campaign files; `PETEK_TARGET` replaces the file's target. Blocking: call on `Dispatchers.IO`. */
    val campaigns: LoadCampaignUseCase by lazy {
        LoadCampaignUseCase(YamlCampaignSource(targetOverride = config.target), DefaultCampaignValidator(templateRenderer))
    }

    /** Names the campaign validator accepts in `run:` steps. */
    val knownRunFunctions: Set<String> get() = RunFunctions.NAMES

    val identityGenerator: IdentityRegistryGenerator by lazy {
        DefaultIdentityRegistryGenerator(
            AzerbaijaniNameCatalog,
            HmacPasswordDeriver(config.identitySecret.reveal().toByteArray(Charsets.UTF_8)),
        )
    }

    val identities: IdentityRepository by lazy {
        SqliteIdentityRepository(database).let { overrides.identitiesDecorator?.invoke(it) ?: it }
    }

    val planIdentities: PlanIdentitiesUseCase by lazy { PlanIdentitiesUseCase(identityGenerator, identities) }

    // --- target, mail, LLM, browser -----------------------------------------------------------------------------

    val oracle: TargetOracle by lazy { resources.track(HttpTargetOracle(config.target, config.testToken)) }

    val mailbox: Mailbox by lazy { resources.track(MailpitMailbox(config.mailpitUrl)) }

    val verification: AwaitVerificationUseCase by lazy { DefaultAwaitVerificationUseCase(mailbox, DefaultVerificationExtractor()) }

    /** LLM usage per agent since the last flush (see [finalizer]). */
    val usageMeter: UsageMeter = UsageMeter()

    private val llmProvider: LlmClient by lazy { overrides.llm ?: resources.track(LlmProviders.create(config)) }

    /** The client agents use: metered, retried and limited to `PETEK_LLM_CONCURRENCY` calls in flight. */
    val llm: LlmClient by lazy {
        MeteredLlmClient(RetryingLlmClient(ConcurrencyLimitedLlmClient(llmProvider, config.llmConcurrency)), usageMeter)
    }

    /**
     * A separate client for `petek doctor`: its own short timeout, no retries, no metering, so the check shows the
     * provider's own answer or error. The caller closes it when it is [AutoCloseable].
     */
    fun diagnosticLlm(): LlmClient = overrides.llm?.let(::NonClosing) ?: LlmProviders.create(config, LlmProviders.DOCTOR_CALL_TIMEOUT)

    private val ownsBrowserEngine = AtomicBoolean(false)

    /** One engine per process; commands start it per run and stop it in `finally`, [close] stops it once more. */
    val browserEngine: BrowserEngine by lazy { overrides.browser ?: PlaywrightBrowserEngine(clock).also { ownsBrowserEngine.set(true) } }

    fun browserConfig(headless: Boolean = config.browserHeadless): BrowserEngineConfig =
        BrowserEngineConfig(headless = headless, topology = config.browserTopology)

    // --- agents, verification, orchestration, reporting ---------------------------------------------------------

    private val watchdog = InactivityWatchdog()

    /** Evidence recorded by agents also proves they are alive (see [InactivityWatchdog]). */
    private val agentRecorder: EvidenceRecorder by lazy { ProgressTrackingRecorder(recorder, watchdog::progress) }

    val runFunctions: RunFunctionRegistry by lazy {
        RunFunctions.standard(oracle, verification, agentRecorder, artifacts, clock, ids)
    }

    val agents: TesterAgentFactory by lazy {
        val protocol = JsonDecisionProtocol()
        val loop =
            DefaultAgentLoop(
                llm = llm,
                protocol = protocol,
                loopDetectorFactory = { ConsecutiveLoopDetector() },
                recorder = agentRecorder,
                artifacts = artifacts,
                verification = verification,
                oracle = oracle,
                prompts = PromptBuilder(protocol),
                resolver = PlaceholderResolver(),
                clock = clock,
                ids = ids,
            )
        DefaultTesterAgentFactory(loop, runFunctions)
    }

    val verifyStep: VerifyStepUseCase by lazy {
        RecordingVerifyStepUseCase(
            DefaultAssertionEvaluator(oracle, templateRenderer, fieldSelector, clock),
            recorder,
            artifacts,
            ids,
        )
    }

    /** Judges a run once and (re)writes its Markdown and HTML report; also used by `petek report`. */
    val finalizeRun: FinalizeRunUseCase by lazy {
        FinalizeRunUseCase(
            runs = runs,
            query = evidenceQuery,
            recorder = recorder,
            artifacts = artifacts,
            judge = ThreeSourceJudge(ids),
            builder = BuildReportUseCase(runs, evidenceQuery, artifacts, IdentityAgentDirectory(identities)),
            writers = listOf(MarkdownReportWriter(), HtmlReportWriter()),
        )
    }

    /** What the runner calls after each run: flush LLM usage, then judge and write the report. */
    val finalizer: RunFinalizer by lazy {
        UsageFlushingFinalizer(usageMeter, recorder, RunFinalizer { runId -> finalizeRun.finalize(runId) })
    }

    val monitor: MonitorView by lazy { overrides.monitor ?: defaultMonitor() }

    /** A runner for one `petek run`; [headless] false shows the browsers (`--headful`). */
    fun campaignRunner(headless: Boolean = config.browserHeadless): CampaignRunner =
        DefaultCampaignRunner(
            identityGenerator = identityGenerator,
            identities = identities,
            runs = runs,
            recorder = recorder,
            artifacts = artifacts,
            browser = browserEngine,
            browserConfig = browserConfig(headless),
            agents = agents,
            verify = verifyStep,
            oracle = oracle,
            fields = fieldSelector,
            renderer = templateRenderer,
            actors = DefaultActorResolver(),
            monitor = monitor,
            finalizer = finalizer,
            clock = clock,
            ids = ids,
            settings = RunnerSettings(mailDomain = config.mailDomain, storageRoot = config.evidenceDir.resolve(STORAGE_STATE_DIRECTORY)),
            sharedStateFactory = ::InMemorySharedRunState,
            watchdog = watchdog,
            diagnostics = MdcDiagnosticContext,
        )

    fun repeatRunner(runner: CampaignRunner): RepeatRunner = DefaultRepeatRunner(runner, ids)

    val teardown: TeardownUseCase by lazy { OracleTeardownUseCase(runs, oracle) }

    // --- explorer (docs/PLAN.md Faza 6) ---------------------------------------------------------------------------

    /** Explorations with their site model versions, findings, events, artifacts and drafts, in the shared database. */
    val explorations: ExplorationRepository by lazy { SqliteExplorationRepository(database) }

    private val ownsExplorerEngine = AtomicBoolean(false)

    /**
     * The explorer's own browser engine, separate from the runs' [browserEngine]: a run stops its engine when it ends,
     * which must never close the pages of an exploration going on at the same time (and vice versa).
     */
    val explorerBrowserEngine: BrowserEngine by lazy {
        overrides.explorerBrowser ?: overrides.browser ?: PlaywrightBrowserEngine(clock).also { ownsExplorerEngine.set(true) }
    }

    /** Campaign drafts from site models, validated exactly like `petek run` validates a campaign file. */
    fun scenarioGenerator(settings: ScenarioSettings = ScenarioSettings()): GenerateScenarioUseCase =
        GenerateScenarioUseCase(
            DefaultCampaignValidator(templateRenderer),
            templateRenderer,
            knownRunFunctions,
            explorations,
            clock,
            ids,
            settings,
        )

    val compareExplorations: CompareExplorationsUseCase by lazy { CompareExplorationsUseCase(explorations) }

    // --- scenario catalog and triage (docs/PLAN.md Faza 7) --------------------------------------------------------

    /** Checks scenario text with the same loader (`PETEK_TARGET` override) and validator as `petek run`. */
    val scenarioValidator: ScenarioValidator by lazy {
        CampaignScenarioValidator(
            YamlCampaignSource(targetOverride = config.target),
            DefaultCampaignValidator(templateRenderer),
            knownRunFunctions,
            config.evidenceDir.resolve(SCENARIO_CHECK_DIRECTORY),
        )
    }

    private val scenarioVersions: ScenarioRepository by lazy { SqliteScenarioRepository(database) }

    private val triageStore: TriageRepository by lazy { SqliteTriageRepository(database) }

    private val scenarioIds: ScenarioIdGenerator = UuidV7ScenarioIdGenerator()

    val scenarioCatalog: ScenarioCatalog by lazy {
        ScenarioCatalog(scenarioVersions, scenarioValidator, FileSystemScenarioFiles(), clock, scenarioIds)
    }

    /**
     * Triage of finished runs. Evidence shown to the model is redacted with every configured secret plus [secrets]
     * (the triaged run's test passwords, which only its identities know): nothing secret reaches the LLM (rule 10).
     */
    fun triage(secrets: Collection<Secret> = emptyList()): TriageRunUseCase =
        TriageRunUseCase(
            llm = llm,
            evidence = evidenceQuery,
            runs = runs,
            scenarios = scenarioVersions,
            triage = triageStore,
            validator = scenarioValidator,
            clock = clock,
            ids = scenarioIds,
            redactor = SecretRedactor(listOfNotNull(config.testToken, config.anthropicApiKey, config.identitySecret) + secrets),
        )

    val triageResults: TriageResults by lazy { TriageResults(triageStore) }

    /**
     * Ends the live board (it draws its final frame) so the terminal can be written to again. Call it after the last
     * run of a command: the board does not draw again afterwards. Safe to call when no board was ever shown.
     */
    fun closeMonitor() {
        resources.closeMonitors()
    }

    override fun close() {
        if (!resources.beginClose()) return
        resources.closeMonitors()
        if (ownsBrowserEngine.get()) {
            runCatching { runBlocking { browserEngine.stop() } }.onFailure { logger.warn(it) { "the browser engine did not stop cleanly" } }
        }
        if (ownsExplorerEngine.get()) {
            runCatching { runBlocking { explorerBrowserEngine.stop() } }
                .onFailure { logger.warn(it) { "the explorer's browser engine did not stop cleanly" } }
        }
        resources.closeAll()
    }

    private fun defaultMonitor(): MonitorView {
        val terminal = Terminal()
        if (!terminal.terminalInfo.outputInteractive) return LoggingMonitorView()
        val board = resources.trackMonitor(MordantMonitorView(terminal))
        return CompositeMonitorView(listOf(board, LoggingMonitorView(KotlinLogging.logger(BOARD_LOGGER))))
    }

    /** An overriding client belongs to the caller: callers may close what [diagnosticLlm] returns without harm. */
    private class NonClosing(
        delegate: LlmClient,
    ) : LlmClient by delegate

    /** Everything the container created and must release, in creation order. */
    private class Resources {
        private val lock = Any()
        private val created = mutableListOf<AutoCloseable>()
        private val monitors = mutableListOf<AutoCloseable>()
        private var closing = false

        fun <T> track(resource: T): T {
            if (resource is AutoCloseable) synchronized(lock) { created += resource }
            return resource
        }

        fun <T : AutoCloseable> trackMonitor(monitor: T): T {
            synchronized(lock) { monitors += monitor }
            return monitor
        }

        fun beginClose(): Boolean =
            synchronized(lock) {
                if (closing) return false
                closing = true
                true
            }

        fun closeMonitors() {
            val open = synchronized(lock) { monitors.toList().also { monitors.clear() } }
            open.forEach { closeQuietly(it) }
        }

        fun closeAll() {
            val open = synchronized(lock) { created.asReversed().toList().also { created.clear() } }
            open.forEach { closeQuietly(it) }
        }

        private fun closeQuietly(resource: AutoCloseable) {
            try {
                resource.close()
            } catch (e: Exception) {
                logger.warn(e) { "${resource::class.simpleName} did not close cleanly" }
            }
        }
    }

    companion object {
        /** Per-agent browser storage state: `<evidence>/storage_state/<run>/<agent>.json`. */
        const val STORAGE_STATE_DIRECTORY = "storage_state"

        /** Logger of the board's log-file copy; `logback.xml` sends it to the file only. */
        const val BOARD_LOGGER = "az.petek.board"

        /** Where scenario texts are written briefly to be loaded and checked: `<evidence>/scenario-checks/`. */
        const val SCENARIO_CHECK_DIRECTORY = "scenario-checks"
    }
}
