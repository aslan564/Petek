package az.petek.app.cli

import az.petek.app.config.IdentitySecretFile
import az.petek.app.config.IdentitySecretSource
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.logging.LoggingSettings
import az.petek.app.logging.LoggingSetup
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the CLI takes from the process around it. Production uses the defaults; tests replace each part (a fixed
 * environment, a temporary working directory, a container with fakes, no global logging changes).
 *
 * @property containers builds the object graph for a loaded configuration; the command closes it.
 * @property observationWindow how long `smoke` and `probe` watch a page's traffic for live-update transports.
 */
class CliRuntime(
    val environment: () -> Map<String, String> = System::getenv,
    val workingDirectory: Path = Path.of("").toAbsolutePath(),
    val identitySecrets: IdentitySecretSource = IdentitySecretFile(IdentitySecretFile.defaultDirectory()),
    val containers: (PetekConfig) -> AppContainer = { AppContainer(it) },
    val configureLogging: (LoggingSettings) -> Unit = LoggingSetup::apply,
    val observationWindow: Duration = 3.seconds,
)
