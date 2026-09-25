package az.petek.app.cli

import az.petek.app.config.PetekConfig
import az.petek.app.diagnostics.SmokeCheck
import com.github.ajalt.clikt.core.Context

/**
 * `petek smoke [--url <url>]`: opens the target (default `PETEK_TARGET`) in Chromium and prints its title, how many
 * interactive elements an agent would see and which live-update transports the page uses; saves
 * `<evidence>/smoke.png`. No login, no writes.
 */
class SmokeCommand : PetekSubcommand("smoke") {
    private val url by urlOption("page to open (default: PETEK_TARGET)")

    override fun help(context: Context): String = "Open the target in Chromium and show what an agent would see."

    override suspend fun execute(): Int =
        withContainer { container ->
            val config = container.config
            val target = url ?: config.target
            TargetGuard.requireAllowed(config.targetPolicy, target)
            val check =
                SmokeCheck(
                    browser = container.browserEngine,
                    browserConfig = container.browserConfig(),
                    screenshot = config.evidenceDir.resolve(SCREENSHOT),
                    observationWindow = session.runtime.observationWindow,
                )
            val result = check.run(target)
            val transports =
                result.network.transports
                    .map { it.name.lowercase() }
                    .sorted()
            echo("Opened ${PetekConfig.masked(target)} (now at ${result.url})")
            echo("Title: ${result.title.ifBlank { "(none)" }}")
            echo("Interactive elements in the snapshot: ${result.elements}")
            echo("Real-time transports: ${transports.joinToString(", ").ifEmpty { "none detected" }}")
            result.network.details.forEach { echo("  $it") }
            echo("Screenshot: ${result.screenshot}")
            ExitCodes.OK
        }

    companion object {
        const val SCREENSHOT = "smoke.png"
    }
}
