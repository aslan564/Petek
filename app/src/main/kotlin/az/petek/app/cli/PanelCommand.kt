package az.petek.app.cli

import az.petek.app.config.ConfigLoader
import az.petek.app.config.PetekConfig
import az.petek.app.demo.DemoTarget
import az.petek.app.di.AppOverrides
import az.petek.app.panel.AppPanelBackend
import az.petek.app.panel.RunStartSignal
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import az.petek.core.time.SystemHarnessClock
import az.petek.dashboard.application.DashboardEvidenceRecorder
import az.petek.dashboard.application.DashboardIdentityRepository
import az.petek.dashboard.application.DashboardRunRepository
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.infrastructure.DashboardServer
import az.petek.evidence.domain.ArtifactStore
import az.petek.orchestration.infrastructure.CompositeMonitorView
import az.petek.orchestration.infrastructure.LoggingMonitorView
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.awaitCancellation
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files

/**
 * `petek panel` (also what `petek` does without a command): serves the web panel on 127.0.0.1 and opens it in the
 * browser; everything is chosen in the page. Without a `.env` it starts the local fake KadroHR and uses it as the
 * target, so the panel always opens. Runs until the process is stopped (IntelliJ's stop button, Ctrl+C).
 */
class PanelCommand : PetekSubcommand(NAME) {
    private val port by option("--port", help = "panel port (default 7070; the next free one when taken)")
        .int()
        .restrictTo(min = 0, max = 65535)
        .default(DEFAULT_PORT)
    private val noOpen by option("--no-open", help = "do not open the browser").flag()
    private val demo by option("--demo", help = "use the local fake KadroHR even when .env exists").flag()

    override fun help(context: Context): String =
        "Open the web panel: instructions, explorer, scenarios, orchestrator, live agents, reports."

    override suspend fun execute(): Int {
        val runtime = session.runtime
        val useDemo = demo || !Files.isRegularFile(runtime.workingDirectory.resolve(CliSession.DEFAULT_ENV_FILE))
        val demoTarget = if (useDemo) DemoTarget(runtime.workingDirectory) else null
        try {
            val config = demoTarget?.let { demoConfig(it) } ?: session.loadConfig()
            session.configureLogging(config, interactive = false)
            val dashboard = LiveDashboard(SystemHarnessClock())
            val runStarts = RunStartSignal()
            val overrides =
                AppOverrides(
                    monitor = CompositeMonitorView(listOf(dashboard, LoggingMonitorView())),
                    recorderDecorator = { DashboardEvidenceRecorder(it, dashboard) },
                    runsDecorator = { runStarts.wrap(DashboardRunRepository(it, dashboard)) },
                    identitiesDecorator = { DashboardIdentityRepository(it, dashboard) },
                )
            runtime.panelContainers(config, overrides).use { container ->
                AppPanelBackend(container, runtime.workingDirectory, RecommendCapacityUseCase(SystemHostResourceProbe()), runStarts)
                    .use { backend ->
                        startServer(dashboard, container.artifacts, backend).use { (_, url) ->
                            echo(
                                "Pətək paneli: $url" +
                                    if (useDemo) "  (hədəf: lokal test saytı ${config.target})" else "  (hədəf: ${config.target})",
                            )
                            if (!noOpen && !runtime.openInBrowser(url.toString())) echo("Brauzeri özünüz açın: $url")
                            awaitCancellation()
                        }
                    }
            }
        } finally {
            demoTarget?.close()
        }
    }

    private fun demoConfig(target: DemoTarget): PetekConfig {
        val file = target.start()
        val runtime = session.runtime
        return ConfigLoader(runtime.environment(), runtime.workingDirectory, runtime.identitySecrets).load(file)
    }

    /** Binds [port], or the next free one when it is taken (another panel, another program). */
    private fun startServer(
        dashboard: LiveDashboard,
        artifacts: ArtifactStore,
        backend: AppPanelBackend,
    ): StartedPanel {
        val candidates = if (port == 0) listOf(0) else (port until port + PORT_ATTEMPTS).toList() + 0
        var last: Exception? = null
        for (candidate in candidates) {
            // Probe first: Ktor reports a taken port as an uncaught exception on a worker thread (noise in the console).
            if (candidate != 0 && !isFree(candidate)) continue
            val server = DashboardServer(dashboard, artifacts, backend = backend, port = candidate)
            try {
                return StartedPanel(server, server.start())
            } catch (e: Exception) {
                runCatching { server.close() }
                last = e
            }
        }
        throw IllegalStateException("The panel could not start on any port", last)
    }

    private fun isFree(port: Int): Boolean =
        runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true } }.getOrDefault(false)

    private data class StartedPanel(
        val server: DashboardServer,
        val url: URI,
    ) : AutoCloseable {
        override fun close() = server.close()
    }

    companion object {
        const val NAME = "panel"
        const val DEFAULT_PORT = 7070
        private const val PORT_ATTEMPTS = 10
    }
}
