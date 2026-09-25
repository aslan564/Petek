package az.petek.app.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError

/** Runs one `petek` command line and returns its exit code instead of exiting, so `main` decides how to stop. */
class PetekCli(
    private val runtime: CliRuntime = CliRuntime(),
) {
    suspend fun execute(argv: List<String>): Int {
        // Started without a command (e.g. IntelliJ's run icon next to main): show how to start instead of failing.
        if (argv.isEmpty()) return gettingStarted()
        val command = PetekCommand(runtime)
        return try {
            command.parse(argv)
            ExitCodes.OK
        } catch (e: CliktError) {
            command.echoFormattedHelp(e)
            exitCodeOf(e)
        }
    }

    private suspend fun gettingStarted(): Int {
        val command = PetekCommand(runtime)
        try {
            command.parse(listOf("--help"))
        } catch (e: CliktError) {
            command.echoFormattedHelp(e)
        }
        command.echo(GETTING_STARTED)
        return ExitCodes.OK
    }

    /**
     * A wrong command line (unknown command, missing argument, `--agents 0`, no command at all) ran nothing, so it is
     * [ExitCodes.CONFIG_OR_ABORTED] like an invalid configuration, never Clikt's default 1, which scripts would read
     * as a FAILED run. Help and the commands' own results keep their codes.
     */
    private fun exitCodeOf(error: CliktError): Int =
        when {
            error is UsageError -> ExitCodes.CONFIG_OR_ABORTED
            error is PrintHelpMessage && error.error -> ExitCodes.CONFIG_OR_ABORTED
            else -> error.statusCode
        }
}

private val GETTING_STARTED =
    """

    Getting started (local demo against the fake KadroHR):
      1. Start the fake target:   ./gradlew :testing:fake-target:run          (IntelliJ: "1. Fake KadroHR")
      2. Check the setup:         petek --env-file .env.fake-target doctor     (IntelliJ: "2. Pətək doctor")
      3. Run a live campaign:     petek --env-file .env.fake-target run scenarios/kadrohr.yaml --agents 12 --headful
                                                                               (IntelliJ: "3. Pətək run")
    In IntelliJ, start Pətək with one of the shared run configurations rather than the run icon next to main():
    they pass the command and the JVM options (--enable-native-access).
    """.trimIndent()
