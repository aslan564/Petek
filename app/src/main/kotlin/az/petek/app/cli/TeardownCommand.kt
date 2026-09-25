package az.petek.app.cli

import az.petek.core.ids.RunId
import az.petek.reporting.domain.RunNotFoundException
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option

/**
 * `petek teardown [--run <run_id>]`: deletes what a run left on the target (its test company), e.g. after a crash or
 * a `--keep-data` run; the latest run when no id is given. Only the run's own target is touched, through the test API,
 * which refuses companies that are not `is_test` (CLAUDE.md rule 8). Idempotent: a second call finds nothing left.
 * Exit code 1 when something could not be removed.
 */
class TeardownCommand : PetekSubcommand("teardown") {
    private val run by option("--run", help = "run id to clean up (default: the latest run)")

    override fun help(context: Context): String = "Delete the test data a run created on the target."

    override suspend fun execute(): Int =
        withContainer { container ->
            val config = container.config
            val record =
                run?.let { id -> RunId(id.trim()).let { container.runs.find(it) ?: throw RunNotFoundException(it) } }
                    ?: container.runs.latest()
            if (record == null) {
                echo("No run is recorded in ${config.dbPath}; nothing to tear down.")
                return@withContainer ExitCodes.OK
            }
            TargetGuard.requireAllowed(config.targetPolicy, config.target)
            TargetGuard.requireRunTarget(record.target, config.target)
            val result = container.teardown.teardown(record.runId)
            if (result.removed.isEmpty() && result.failures.isEmpty()) {
                echo("Run ${record.runId}: nothing left to tear down.")
            }
            result.removed.forEach { echo("Run ${record.runId}: removed $it") }
            result.failures.forEach { echo("Run ${record.runId}: could not remove $it", err = true) }
            if (result.failures.isEmpty()) ExitCodes.OK else ExitCodes.FAILURE
        }
}
