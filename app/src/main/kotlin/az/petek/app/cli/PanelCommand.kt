package az.petek.app.cli

import az.petek.app.config.ConfigLoader
import az.petek.app.config.PetekConfig
import az.petek.app.demo.DemoTarget
import az.petek.app.panel.WebPanel
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.infrastructure.SystemHostResourceProbe
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.awaitCancellation
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
            WebPanel
                .start(
                    config = config,
                    containers = runtime.panelContainers,
                    workingDirectory = runtime.workingDirectory,
                    capacityAdvice = RecommendCapacityUseCase(SystemHostResourceProbe()),
                    port = port,
                ).use { panel ->
                    val url = panel.url
                    echo(
                        "Pətək paneli: $url" +
                            if (useDemo) "  (hədəf: lokal test saytı ${config.target})" else "  (hədəf: ${config.target})",
                    )
                    if (!noOpen && !runtime.openInBrowser(url.toString())) echo("Brauzeri özünüz açın: $url")
                    awaitCancellation()
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

    companion object {
        const val NAME = "panel"
        const val DEFAULT_PORT = 7070
    }
}
