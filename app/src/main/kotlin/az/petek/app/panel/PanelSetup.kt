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

package az.petek.app.panel

import az.petek.app.config.PetekConfig
import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.diagnostics.TargetReachability
import az.petek.app.init.InitTemplates
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.SetupAnswer
import az.petek.dashboard.domain.SiteSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * What `petek panel` does with the site the owner names on the setup page (AGENTS.md rule 12): the address must be a
 * full http(s) address that answers (a site that does not, or answers only with a CDN's error page, is refused with
 * the reason, and nothing is written); it is then written to [envFile] from the configuration template (`petek init`'s,
 * never over an existing file, `rw-------` since the file will hold secrets), and [open] starts the panel for it. Once
 * the panel runs, every further answer points at it.
 */
internal class PanelSetup(
    private val envFile: Path,
    private val templates: InitTemplates,
    private val reachability: TargetReachability,
    /** Loads the configuration just written and starts the panel; returns where it runs. */
    private val open: suspend () -> URI,
) : SiteSetup {
    private val mutex = Mutex()
    private var panel: URI? = null

    override suspend fun configure(target: String): SetupAnswer =
        mutex.withLock {
            panel?.let { return SetupAnswer.Ready(it.toString()) }
            val url =
                try {
                    PanelTargets.parse(target, FIELD)
                } catch (e: PanelRequestException) {
                    return SetupAnswer.Refused(e.message.orEmpty())
                }
            val answer = reachability.check(url)
            if (answer is TargetAnswer.Unreachable) {
                return SetupAnswer.Refused(
                    "Sayt cavab vermir: ${PetekConfig.masked(url)} (${answer.reason}). Heç nə yazılmadı; saytın işlədiyini " +
                        "yoxlayın və ya başqa ünvan yazın.",
                )
            }
            try {
                withContext(Dispatchers.IO) { write(url) }
            } catch (_: FileAlreadyExistsException) {
                return SetupAnswer.Refused("$envFile artıq var; Pətəki yenidən başladın, o bu faylı oxuyacaq.")
            }
            val started = withContext(Dispatchers.IO) { open() }
            panel = started
            SetupAnswer.Ready(started.toString())
        }

    private fun write(url: URI) {
        envFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(envFile, templates.env(url), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        runCatching { Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-------")) }
    }

    private companion object {
        const val FIELD = "target"
    }
}
