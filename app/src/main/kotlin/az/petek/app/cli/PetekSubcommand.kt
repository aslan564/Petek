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

import az.petek.app.config.ConfigException
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.diagnostics.TargetUnreachableException
import az.petek.ownership.domain.OwnershipRequiredException
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
            is ConfigException, is TargetRefusedException, is TargetUnreachableException, is OwnershipRequiredException -> {
                ExitCodes.CONFIG_OR_ABORTED
            }

            else -> {
                ExitCodes.FAILURE
            }
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

    /** `--json` was given: the command prints [emitJson] instead of its tables and prose. */
    protected val json: Boolean get() = session.json

    /** Prints [element] as one compact JSON line on stdout (the `--json` result). */
    protected fun emitJson(element: JsonElement) {
        echo(JSON.encodeToString(JsonElement.serializer(), element))
    }

    private fun fail(error: Exception) {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown error"
        echo("Error: $reason", err = true)
        if (session.verbose) echo(error.stackTraceToString(), err = true)
        // Scripts reading stdout get the failure there too, as a document with only an error.
        if (session.json) emitJson(buildJsonObject { put("error", reason) })
    }

    private companion object {
        val JSON = Json { prettyPrint = false }
    }
}
