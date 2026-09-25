package az.petek.app.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError

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
            e.statusCode
        }
    }
}
