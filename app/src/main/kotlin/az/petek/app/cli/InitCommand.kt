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

import az.petek.app.init.HostAi
import az.petek.app.init.ProjectInitializer
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path

/**
 * `petek init [--target <url>] [--ai claude,codex,cursor,gemini,copilot|all] [--force] [DIR]`: prepares a project so
 * Pətək runs next to it and the project's AI coding agent knows how to drive it (see [ProjectInitializer]). Without
 * `--ai` the agents are detected from the project's files (CLAUDE.md, AGENTS.md, .cursor, GEMINI.md, Copilot
 * instructions); with none recognisable, Claude Code and AGENTS.md are written. No configuration is loaded: `init`
 * is what creates it.
 */
class InitCommand(
    private val initializer: ProjectInitializer = ProjectInitializer(),
) : PetekSubcommand("init") {
    private val target by urlOption("site under test, written to PETEK_TARGET in .env and to .petek/petek.yaml", name = "--target")
    private val ais by option(
        "--ai",
        help = "AI coding agents to write instructions for: ${HostAi.entries.joinToString(",") { it.key }} or all (default: detected)",
    ).convert { HostAi.parse(it) }
    private val force by option(
        "--force",
        help = "rewrite the files Pətək owns (.petek/*, skill copies) from the current templates; .env is never rewritten",
    ).flag()
    private val directory by option("--dir", help = "project directory (default: the working directory)", metavar = "PATH").path()

    override fun help(context: Context): String =
        "Prepare this project for Pətək: .env, .petek/, the skill pack and MCP entries for your AI coding agent."

    override suspend fun execute(): Int {
        val project: Path = directory?.let { session.resolve(it) } ?: session.runtime.workingDirectory
        val result =
            withContext(Dispatchers.IO) {
                initializer.initialize(ProjectInitializer.Request(project, target, ais, force))
            }
        if (json) {
            emitJson(
                buildJsonObject {
                    put("directory", project.toAbsolutePath().toString())
                    putJsonArray("ais") { result.ais.forEach { add(it.key) } }
                    put("detected", result.detected)
                    putJsonArray("changes") {
                        result.changes.forEach { change ->
                            addJsonObject {
                                put("path", change.path)
                                put("outcome", change.outcome.name)
                                put("note", change.note)
                            }
                        }
                    }
                },
            )
            return ExitCodes.OK
        }
        val how = if (result.detected) "detected" else "requested"
        echo("Pətək in ${project.toAbsolutePath()} for ${result.ais.joinToString(", ") { it.key }} ($how):")
        result.changes.forEach { change ->
            val note = if (change.note.isEmpty()) "" else " — ${change.note}"
            echo("  ${change.outcome.name.lowercase().padEnd(9)} ${change.path}$note")
        }
        echo("")
        echo(
            "Next: fill .env (PETEK_TARGET, and PETEK_TEST_TOKEN + PETEK_IDENTITY_SECRET for full runs), then `petek doctor` and `petek panel`.",
        )
        return ExitCodes.OK
    }
}
