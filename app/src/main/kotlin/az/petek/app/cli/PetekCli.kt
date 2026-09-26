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

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError

/** Runs one `petek` command line and returns its exit code instead of exiting, so `main` decides how to stop. */
class PetekCli(
    private val runtime: CliRuntime = CliRuntime(),
) {
    suspend fun execute(argv: List<String>): Int {
        // Started without a command (IntelliJ's run icon next to main): open the web panel, where the owner chooses
        // everything. Nothing is ever read from the terminal.
        val command = PetekCommand(runtime)
        return try {
            command.parse(effectiveArguments(argv))
            ExitCodes.OK
        } catch (e: CliktError) {
            command.echoFormattedHelp(e)
            exitCodeOf(e)
        }
    }

    /**
     * A wrong command line (unknown command, missing argument, `--testers 0`, no command at all) ran nothing, so it is
     * [ExitCodes.CONFIG_OR_ABORTED] like an invalid configuration, never Clikt's default 1, which scripts would read
     * as a FAILED run. Help and the commands' own results keep their codes.
     */
    private fun exitCodeOf(error: CliktError): Int =
        when {
            error is UsageError -> ExitCodes.CONFIG_OR_ABORTED
            error is PrintHelpMessage && error.error -> ExitCodes.CONFIG_OR_ABORTED
            else -> error.statusCode
        }

    companion object {
        /** No command means the web panel: IntelliJ's run icon starts `petek` without arguments. */
        fun effectiveArguments(argv: List<String>): List<String> = argv.ifEmpty { listOf(PanelCommand.NAME) }
    }
}
