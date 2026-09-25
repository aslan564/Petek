package az.petek.app.cli

import az.petek.app.config.ConfigException
import az.petek.app.config.ConfigLoader
import az.petek.app.config.PetekConfig
import az.petek.app.logging.LoggingSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * The global options of one `petek` invocation, handed from the root command to the subcommand that runs.
 * Loading the configuration is left to the subcommand, so `doctor` can report an invalid configuration as a check.
 */
class CliSession(
    val runtime: CliRuntime,
    /** `--env-file`; null means `.env` in the working directory, which may be absent. */
    private val envFile: Path?,
    val verbose: Boolean,
) {
    /** @throws ConfigException listing every problem of `.env` and the environment. */
    fun loadConfig(): PetekConfig {
        val explicit = envFile?.let { runtime.workingDirectory.resolve(it) }
        if (explicit != null && !Files.isRegularFile(explicit)) {
            throw ConfigException(listOf("--env-file $explicit does not exist or is not a file"))
        }
        val file = explicit ?: runtime.workingDirectory.resolve(DEFAULT_ENV_FILE)
        return ConfigLoader(runtime.environment(), runtime.workingDirectory, runtime.identitySecrets).load(file)
    }

    /** Points logging at the configuration's evidence directory; [interactive] = stdout shows the live board. */
    fun configureLogging(
        config: PetekConfig,
        interactive: Boolean,
    ) {
        runtime.configureLogging(LoggingSettings(config.logDirectory, verbose, interactive))
    }

    /** Resolves a path given on the command line against the working directory. */
    fun resolve(path: Path): Path = runtime.workingDirectory.resolve(path).normalize()

    companion object {
        const val DEFAULT_ENV_FILE = ".env"
    }
}
