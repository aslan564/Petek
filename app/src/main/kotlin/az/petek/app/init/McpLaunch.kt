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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * How the project's AI coding agent starts Pətək's MCP server (`petek mcp` over stdio) from the agent's MCP file. A
 * bare `petek` works only where it is on PATH: after `npx petek init` nothing is, and neither is a bundle extracted
 * anywhere, so the agent could not start the server at all. [note] tells the owner when the entry is this machine's
 * only.
 */
data class McpLaunch(
    val command: String,
    val args: List<String>,
    val note: String = "",
) {
    /** The server entry of an MCP file: `{"command": …, "args": […]}`. */
    fun entry(): JsonObject =
        buildJsonObject {
            put("command", command)
            putJsonArray("args") { args.forEach { add(JsonPrimitive(it)) } }
        }

    companion object {
        /** `petek mcp` from PATH: the same on every machine that installed Pətək. */
        val ON_PATH = McpLaunch("petek", listOf("mcp"))

        /** Set by the npm launcher (`npx petek`, or a global npm install) for the bundle it starts: `npm@<version>`. */
        const val LAUNCHER = "PETEK_LAUNCHER"

        /**
         * How this Pətək was started, from the process [environment] and its bundle's directory [home] (the
         * `-Dpetek.home` of the bundle's launcher): `petek` when it is on PATH ([onPath]); `npx -y petek@<version> mcp`
         * when the npm launcher started it; else the bundle's own launcher by its absolute path (this machine only);
         * `petek` again when none is known (run from source).
         */
        fun of(
            environment: Map<String, String>,
            home: String?,
            onPath: (String) -> Boolean,
        ): McpLaunch {
            if (onPath(COMMAND)) return ON_PATH
            val npm = environment[LAUNCHER]?.takeIf { it.startsWith(NPM) }?.removePrefix(NPM)?.takeIf { it.isNotBlank() }
            if (npm != null) return McpLaunch("npx", listOf("-y", "petek@$npm", "mcp"))
            val bundle = home?.takeIf { it.isNotBlank() } ?: return ON_PATH
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
            val launcher = Path.of(bundle, "bin", if (windows) "petek.cmd" else COMMAND).toAbsolutePath().normalize()
            return McpLaunch(launcher.toString(), listOf("mcp"), "this machine's path to Pətək; put petek on PATH to share the file")
        }

        /** Whether an executable [command] is on the [path] (the `PATH` of the environment). */
        fun onPath(
            command: String,
            path: String?,
        ): Boolean =
            path
                .orEmpty()
                .split(java.io.File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .any { dir ->
                    listOf(command, "$command.cmd", "$command.exe").any { name ->
                        val file = Path.of(dir, name)
                        Files.isRegularFile(file) && Files.isExecutable(file)
                    }
                }

        private const val COMMAND = "petek"
        private const val NPM = "npm@"
    }
}
