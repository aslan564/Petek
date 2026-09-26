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
import az.petek.app.config.ConfigLoader
import az.petek.app.config.PetekConfig
import az.petek.app.logging.LoggingSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * The global options of one `petek` invocation, handed from the root command to the subcommand that runs.
 * Loading the configuration is left to the subcommand, so `doctor` can report an invalid configuration as a check.
 */
class CliSession(
    val runtime: CliRuntime,
    /** `--env-file`; null means `.env` in the working directory, which may be absent. */
    private val envFile: Path?,
    val verbose: Boolean,
    /** `--json`: the command prints its result as one JSON document (R10); tables and prose stay off stdout. */
    val json: Boolean = false,
) {
    /**
     * Whether this invocation names a configuration: `--env-file` was given (a missing one is [loadConfig]'s error), or
     * `.env` exists in the working directory. `petek panel` falls back to the local demo only without one.
     */
    val hasConfigurationFile: Boolean
        get() = envFile != null || Files.isRegularFile(runtime.workingDirectory.resolve(DEFAULT_ENV_FILE))

    /** @throws ConfigException listing every problem of `.env` and the environment. */
    fun loadConfig(): PetekConfig {
        val explicit = envFile?.let { runtime.workingDirectory.resolve(it) }
        if (explicit != null && !Files.isRegularFile(explicit)) {
            throw ConfigException(listOf("--env-file $explicit does not exist or is not a file"))
        }
        val file = explicit ?: runtime.workingDirectory.resolve(DEFAULT_ENV_FILE)
        return ConfigLoader(runtime.environment(), runtime.workingDirectory, runtime.identitySecrets).load(file)
    }

    /** Points logging at the configuration's evidence directory; [interactive] = stdout shows the live board. */
    fun configureLogging(
        config: PetekConfig,
        interactive: Boolean,
    ) {
        runtime.configureLogging(LoggingSettings(config.logDirectory, verbose, interactive))
    }

    /** Resolves a path given on the command line against the working directory. */
    fun resolve(path: Path): Path = runtime.workingDirectory.resolve(path).normalize()

    companion object {
        const val DEFAULT_ENV_FILE = ".env"
    }
}
