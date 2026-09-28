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
 *
 * Where the configuration comes from, first match wins:
 * 1. `--env-file`;
 * 2. `.env` in the working directory (a project prepared by `petek init`, or any directory with its own `.env`);
 * 3. the environment alone, when it names the site itself (`PETEK_TARGET`, as CI does);
 * 4. the owner's own workspace ([CliRuntime.workspace]): its `.env`, written by the panel's setup page, so Pətək works
 *    the same from any directory once it knows the site.
 *
 * The first three are exactly how Pətək has always read its configuration; the workspace is only used when none of
 * them names one.
 */
class CliSession(
    val runtime: CliRuntime,
    /** `--env-file`; null means `.env` in the working directory, which may be absent. */
    private val envFile: Path?,
    val verbose: Boolean,
    /** `--json`: the command prints its result as one JSON document (R10); tables and prose stay off stdout. */
    val json: Boolean = false,
) {
    private val localEnvFile: Path get() = runtime.workingDirectory.resolve(DEFAULT_ENV_FILE)

    /** The workspace's `.env` when this invocation takes its configuration from there ([CliSession] point 4); else null. */
    private val workspaceEnvFile: Path?
        get() {
            if (envFile != null || Files.isRegularFile(localEnvFile)) return null
            if (!runtime.environment()[TARGET_KEY].isNullOrBlank()) return null
            return runtime.workspace.resolve(DEFAULT_ENV_FILE).takeIf { Files.isRegularFile(it) }
        }

    /**
     * Whether this invocation names a configuration file: `--env-file` was given (a missing one is [loadConfig]'s error),
     * `.env` exists in the working directory, or the owner's workspace has one. Without any, `petek panel` asks for the site.
     */
    val hasConfigurationFile: Boolean
        get() = envFile != null || Files.isRegularFile(localEnvFile) || workspaceEnvFile != null

    /**
     * Whether this invocation names the site to test: a configuration file ([hasConfigurationFile]) or `PETEK_TARGET` in
     * the environment. Only without either does `petek panel` ask for the site and `petek mcp` answer that none is given
     * (rule 12); asking while the environment names one would be answered and then overridden by it.
     */
    val namesSite: Boolean
        get() = hasConfigurationFile || !runtime.environment()[TARGET_KEY].isNullOrBlank()

    /**
     * The directory the configuration's relative paths (evidence, scenarios, target profiles) belong to: the owner's
     * workspace when the configuration comes from there, else the working directory.
     */
    val configurationDirectory: Path
        get() = if (workspaceEnvFile != null) runtime.workspace else runtime.workingDirectory

    /** The configuration file this invocation reads, when there is one (`--env-file`, `.env` here, or the workspace's). */
    val configurationFile: Path?
        get() =
            envFile?.let { runtime.workingDirectory.resolve(it) }
                ?: localEnvFile.takeIf { Files.isRegularFile(it) }
                ?: workspaceEnvFile

    /** Where the panel's setup page writes the site the owner names when no configuration exists yet. */
    val setupEnvFile: Path get() = runtime.workspace.resolve(DEFAULT_ENV_FILE)

    /** @throws ConfigException listing every problem of `.env` and the environment. */
    fun loadConfig(): PetekConfig {
        val explicit = envFile?.let { runtime.workingDirectory.resolve(it) }
        if (explicit != null && !Files.isRegularFile(explicit)) {
            throw ConfigException(listOf("--env-file $explicit does not exist or is not a file"))
        }
        workspaceEnvFile?.let { workspace ->
            return ConfigLoader(runtime.environment(), runtime.workspace, runtime.identitySecrets).load(workspace)
        }
        val file = explicit ?: localEnvFile
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
        private const val TARGET_KEY = "PETEK_TARGET"
    }
}
