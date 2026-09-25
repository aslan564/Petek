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
        val command = PetekCommand(runtime)
        return try {
            command.parse(argv)
            ExitCodes.OK
        } catch (e: CliktError) {
            command.echoFormattedHelp(e)
            exitCodeOf(e)
        }
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
