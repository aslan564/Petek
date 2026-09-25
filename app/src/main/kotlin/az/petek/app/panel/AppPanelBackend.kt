package az.petek.app.panel

import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.panel.explorer.AnswerBook
import az.petek.app.panel.explorer.PanelExplorerAdapter
import az.petek.app.panel.explorer.RoleSessionSource
import az.petek.app.panel.explorer.SetupRuns
import az.petek.app.panel.explorer.TestCompanyRoleSessions
import az.petek.app.panel.runs.PanelRunWatch
import az.petek.app.panel.runs.PanelRunsAdapter
import az.petek.app.panel.runs.RunTargets
import az.petek.app.panel.scenarios.PanelScenariosAdapter
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.domain.LimitingFactor
import az.petek.core.ids.ArtifactId
import az.petek.dashboard.domain.CapacityLimit
import az.petek.dashboard.domain.CapacityView
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelCapacity
import az.petek.dashboard.domain.PanelExplorer
import az.petek.dashboard.domain.PanelRuns
import az.petek.dashboard.domain.PanelScenarios
import az.petek.evidence.domain.ArtifactRecord
import az.petek.orchestration.domain.MonitorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path

/**
 * The web panel's backend in the composition root: one adapter per area of [PanelBackend], each over the real use
 * cases of the container, joined by delegation.
 *
 * - [PanelCapacity]: capacity advice for the tester count ([RecommendCapacityUseCase]).
 * - [PanelExplorer]: the explorer agent on the instruction form's site ([PanelExplorerAdapter]).
 * - [PanelScenarios]: the versioned scenario catalog, the owner's `scenarios/` files and the explorer's drafts
 *   ([PanelScenariosAdapter]).
 * - [PanelRuns]: runs of approved versions, history, reports, stability and triage ([PanelRunsAdapter]).
 *
 * Artifacts the panel may serve besides the live board's are an exploration's captures and the evidence a shown triage
 * verdict cites. [close] cancels whatever still runs (a run still tears down and writes its report).
 */
internal class AppPanelBackend(
    private val capacity: PanelCapacity,
    private val explorer: PanelExplorer,
    private val scenarios: PanelScenarios,
    private val runs: PanelRunsAdapter,
    private val scope: CoroutineScope,
) : PanelBackend,
    PanelCapacity by capacity,
    PanelExplorer by explorer,
    PanelScenarios by scenarios,
    PanelRuns by runs,
    AutoCloseable {
    override suspend fun explorationArtifact(artifactId: ArtifactId): ArtifactRecord? =
        explorer.explorationArtifact(artifactId) ?: runs.evidenceArtifact(artifactId)

    override fun close() {
        scope.cancel()
    }

    companion object {
        /**
         * Builds the panel's backend over [container] (the panel's own object graph, decorated for the live board):
         * [workingDirectory] holds `scenarios/`, [board] receives harness messages, [watch] must be the watch whose
         * decorators wrap [container]'s repositories, and [derive] builds the container of a run against another site
         * (see [RunTargets]). [roleSessions] replaces the explorer's test-company sessions (tests).
         */
        fun create(
            container: AppContainer,
            workingDirectory: Path,
            capacityAdvice: RecommendCapacityUseCase,
            watch: PanelRunWatch,
            board: MonitorView,
            derive: (PetekConfig) -> AppContainer,
            roleSessions: ((SetupRuns) -> RoleSessionSource)? = null,
        ): AppPanelBackend {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val answers =
                AnswerBook(
                    container.config.evidenceDir
                        .resolve(EXPLORER_DIRECTORY)
                        .resolve(AnswerBook.FILE_NAME),
                )
            lateinit var runs: PanelRunsAdapter
            val sessions =
                RoleSessionSource { request, factory, progress ->
                    val source = roleSessions?.invoke(runs) ?: TestCompanyRoleSessions(container, runs)
                    source.open(request, factory, progress)
                }
            val explorer = PanelExplorerAdapter(container, sessions, answers, scope)
            val scenarios = PanelScenariosAdapter(container, explorer, workingDirectory.resolve(SCENARIO_DIRECTORY), scope)
            runs = PanelRunsAdapter(container, scenarios, RunTargets(container, derive), watch, board, scope)
            return AppPanelBackend(CapacityAdapter(capacityAdvice), explorer, scenarios, runs, scope)
        }

        const val SCENARIO_DIRECTORY = "scenarios"

        /** The explorer's own files next to the evidence: `<evidence>/explorer/`. */
        const val EXPLORER_DIRECTORY = "explorer"
    }
}

/** Capacity advice as the instruction screen shows it: the fast estimate, never a limit. */
internal class CapacityAdapter(
    private val advice: RecommendCapacityUseCase,
) : PanelCapacity {
    override suspend fun capacity(testers: Int): CapacityView {
        val recommended = advice.execute(contextsPerBrowser = BrowserEngineConfig.DEFAULT_CONTEXTS_PER_BROWSER)
        return CapacityView(
            requested = testers,
            recommended = recommended.maxTesters,
            limitingFactor = if (recommended.limitingFactor == LimitingFactor.CPU) CapacityLimit.CPU else CapacityLimit.MEMORY,
            availableMemoryMb = recommended.host.availableMemoryBytes / MIB,
            totalMemoryMb = recommended.host.totalMemoryBytes / MIB,
            cpuCores = recommended.host.cpuCores,
            measured = recommended.perSession.measured,
            notes = recommended.notes,
        )
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
