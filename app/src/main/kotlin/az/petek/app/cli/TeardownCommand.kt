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

import az.petek.core.ids.RunId
import az.petek.reporting.domain.RunNotFoundException
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `petek teardown [--run <run_id>]`: deletes what a run left on the target (its test company), e.g. after a crash or
 * a `--keep-data` run; the latest run when no id is given. Only the run's own target is touched, through the test API,
 * which refuses companies that are not `is_test` (AGENTS.md rule 8). Idempotent: a second call finds nothing left.
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
                if (json) {
                    emitJson(
                        teardownJson(null, emptyList(), emptyList()),
                    )
                } else {
                    echo("No run is recorded in ${config.dbPath}; nothing to tear down.")
                }
                return@withContainer ExitCodes.OK
            }
            TargetGuard.requireAllowed(config.targetPolicy, config.target)
            TargetGuard.requireRunTarget(record.target, config.target)
            val result = container.teardown.teardown(record.runId)
            if (json) {
                emitJson(teardownJson(record.runId, result.removed, result.failures))
                return@withContainer if (result.failures.isEmpty()) ExitCodes.OK else ExitCodes.FAILURE
            }
            if (result.removed.isEmpty() && result.failures.isEmpty()) {
                echo("Run ${record.runId}: nothing left to tear down.")
            }
            result.removed.forEach { echo("Run ${record.runId}: removed $it") }
            result.failures.forEach { echo("Run ${record.runId}: could not remove $it", err = true) }
            if (result.failures.isEmpty()) ExitCodes.OK else ExitCodes.FAILURE
        }

    private fun teardownJson(
        runId: RunId?,
        removed: List<String>,
        failures: List<String>,
    ) = buildJsonObject {
        put("runId", runId?.value)
        putJsonArray("removed") { removed.forEach { add(it) } }
        putJsonArray("failures") { failures.forEach { add(it) } }
    }
}
