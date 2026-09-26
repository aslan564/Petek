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

import az.petek.app.config.IdentitySecretFile
import az.petek.app.config.IdentitySecretSource
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.logging.LoggingSettings
import az.petek.app.logging.LoggingSetup
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the CLI takes from the process around it. Production uses the defaults; tests replace each part (a fixed
 * environment, a temporary working directory, a container with fakes, no global logging changes).
 *
 * @property containers builds the object graph for a loaded configuration; the command closes it.
 * @property observationWindow how long `smoke` and `probe` watch a page's traffic for live-update transports.
 * @property panelContainers builds the object graph of `petek panel`, with the overrides that connect it to the live board.
 * @property openInBrowser opens a local file or URL in the user's browser; returns false when that is impossible.
 */
class CliRuntime(
    val environment: () -> Map<String, String> = System::getenv,
    val workingDirectory: Path = Path.of("").toAbsolutePath(),
    val identitySecrets: IdentitySecretSource = IdentitySecretFile(IdentitySecretFile.defaultDirectory()),
    val containers: (PetekConfig) -> AppContainer = { AppContainer(it) },
    val configureLogging: (LoggingSettings) -> Unit = LoggingSetup::apply,
    val observationWindow: Duration = 3.seconds,
    val panelContainers: (PetekConfig, AppOverrides) -> AppContainer = { config, overrides -> AppContainer(config, overrides) },
    val openInBrowser: (String) -> Boolean = BrowserOpener::open,
    /** The process's stdin and stdout, which `petek mcp` speaks its protocol over; tests pass pipes. */
    val standardInput: InputStream = System.`in`,
    val standardOutput: OutputStream = FileOutputStream(FileDescriptor.out),
)
