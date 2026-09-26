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

package az.petek.app.init

import java.nio.file.Files
import java.nio.file.Path

/**
 * The AI coding agents `petek init` can write instructions for (R10), each with the files whose presence in a project
 * shows it is in use ([markers]), the shared instruction file it reads ([instructionFile], the Pətək fragment is
 * appended there between markers) and, where the agent reads a project-level MCP configuration, that file and the
 * key its servers live under.
 */
enum class HostAi(
    val key: String,
    val markers: List<String>,
    val instructionFile: String,
    val mcpFile: String?,
    val mcpServersKey: String = "mcpServers",
) {
    /** `AGENTS.md`, the instruction file most coding agents read, and the project-level `.mcp.json` many of them load. */
    AGENTS("agents", listOf("AGENTS.md", ".codex", ".mcp.json"), "AGENTS.md", ".mcp.json"),
    CURSOR("cursor", listOf(".cursor"), ".cursor/rules/petek.mdc", ".cursor/mcp.json"),
    GEMINI("gemini", listOf("GEMINI.md", ".gemini"), "GEMINI.md", ".gemini/settings.json"),
    COPILOT("copilot", listOf(".github/copilot-instructions.md"), ".github/copilot-instructions.md", ".vscode/mcp.json", "servers"),
    ;

    /** Other names `--ai` accepts for this entry (`codex` reads `AGENTS.md`). */
    val aliases: Set<String> get() = if (this == AGENTS) setOf("codex") else emptySet()

    /** Whether [project] carries one of this agent's marker files. */
    fun isUsedIn(project: Path): Boolean = markers.any { Files.exists(project.resolve(it)) }

    companion object {
        const val ALL = "all"

        /** The agents used in [project] by their markers; empty when none is recognisable. */
        fun detect(project: Path): Set<HostAi> = entries.filter { it.isUsedIn(project) }.toSet()

        /** Parses a comma-separated `--ai` value (`all` means every agent); throws on an unknown name. */
        fun parse(value: String): Set<HostAi> =
            value
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .flatMapTo(linkedSetOf()) { name ->
                    if (name ==
                        ALL
                    ) {
                        entries
                    } else {
                        listOf(requireNotNull(entries.find { it.key == name || name in it.aliases }) { unknown(name) })
                    }
                }

        private fun unknown(name: String) = "unknown AI '$name'; use ${entries.joinToString(", ") { it.key }} or $ALL"
    }
}
