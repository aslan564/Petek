/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.app.cli

import az.petek.core.error.PetekException
import az.petek.core.ids.RunId
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `petek report <run_id|latest>`: rebuilds the Markdown and HTML report of a stored run from its evidence. A finished
 * run is judged only once (at its end); a run that is still RUNNING (or crashed) gets provisional findings in the
 * report and none in the store. With `--pdf` the report is also printed as `report.pdf` by Chromium (Faza 12). Nothing
 * touches the target.
 */
class ReportCommand : PetekSubcommand("report") {
    private val run by argument("run_id", help = "a run id such as run_0192…, or 'latest'")
    private val pdf by option("--pdf", help = "also print the report as report.pdf (Chromium's own print)").flag()

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
            val printed = if (pdf) container.reportPdf.export(runId) else null
            if (json) {
                emitJson(
                    buildJsonObject {
                        put("runId", runId.value)
                        put("html", directory.resolve(RunCommand.HTML_REPORT).toAbsolutePath().toString())
                        put("markdown", directory.resolve(MARKDOWN_REPORT).toAbsolutePath().toString())
                        printed?.let { put("pdf", it.toAbsolutePath().toString()) }
                    },
                )
                return@withContainer ExitCodes.OK
            }
            echo("Report of $runId: ${directory.resolve(RunCommand.HTML_REPORT)}")
            echo("Markdown: ${directory.resolve(MARKDOWN_REPORT)}")
            printed?.let { echo("PDF: $it") }
            ExitCodes.OK
        }

    companion object {
        const val LATEST = "latest"
        const val MARKDOWN_REPORT = "report.md"
    }
}
