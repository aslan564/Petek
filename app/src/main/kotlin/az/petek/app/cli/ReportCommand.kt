package az.petek.app.cli

import az.petek.core.error.PetekException
import az.petek.core.ids.RunId
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument

/**
 * `petek report <run_id|latest>`: rebuilds the Markdown and HTML report of a stored run from its evidence. A finished
 * run is judged only once (at its end); a run that is still RUNNING (or crashed) gets provisional findings in the
 * report and none in the store. Nothing touches the target.
 */
class ReportCommand : PetekSubcommand("report") {
    private val run by argument("run_id", help = "a run id such as run_0192…, or 'latest'")

    override fun help(context: Context): String = "Regenerate the report of a stored run and print its path."

    override suspend fun execute(): Int =
        withContainer { container ->
            val runId =
                if (run.equals(LATEST, ignoreCase = true)) {
                    container.runs.latest()?.runId ?: throw PetekException("No run is recorded in ${container.config.dbPath} yet")
                } else {
                    RunId(run.trim())
                }
            val directory = container.finalizeRun.finalize(runId)
            echo("Report of $runId: ${directory.resolve(RunCommand.HTML_REPORT)}")
            echo("Markdown: ${directory.resolve(MARKDOWN_REPORT)}")
            ExitCodes.OK
        }

    companion object {
        const val LATEST = "latest"
        const val MARKDOWN_REPORT = "report.md"
    }
}
