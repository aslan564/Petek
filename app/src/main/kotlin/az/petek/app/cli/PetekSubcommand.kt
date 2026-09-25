package az.petek.app.cli

import az.petek.app.config.ConfigException
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import kotlinx.coroutines.CancellationException

/**
 * Common behaviour of every `petek` subcommand: the configuration and the container are built only when the command
 * runs, and every failure ends as a one-line reason on stderr (the stack trace only with `--verbose`) and an exit
 * code from [ExitCodes], never as an uncaught exception.
 */
abstract class PetekSubcommand(
    name: String,
) : SuspendingCliktCommand(name) {
    protected val session: CliSession by requireObject()

    /** Runs the command and returns its exit code. */
    protected abstract suspend fun execute(): Int

    /** The exit code of a failure; configuration and target refusals are 2, anything else 1 unless overridden. */
    protected open fun exitCodeFor(error: Exception): Int =
        when (error) {
            is ConfigException, is TargetRefusedException -> ExitCodes.CONFIG_OR_ABORTED
            else -> ExitCodes.FAILURE
        }

    final override suspend fun run() {
        val status =
            try {
                execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CliktError) {
                throw e
            } catch (e: Exception) {
                fail(e)
                exitCodeFor(e)
            }
        if (status != ExitCodes.OK) throw ProgramResult(status)
    }

    /**
     * Loads the configuration, points logging at its evidence directory, builds the container for [block] and closes
     * it afterwards, also on failure.
     */
    protected suspend fun <T> withContainer(block: suspend (AppContainer) -> T): T {
        val config = session.loadConfig()
        session.configureLogging(config, interactive = terminal.terminalInfo.outputInteractive)
        return session.runtime.containers(config).use { block(it) }
    }

    protected val PetekConfig.targetLabel: String get() = PetekConfig.masked(target)

    private fun fail(error: Exception) {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown error"
        echo("Error: $reason", err = true)
        if (session.verbose) echo(error.stackTraceToString(), err = true)
    }
}
