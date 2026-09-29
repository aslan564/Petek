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

import az.petek.campaign.infrastructure.YamlTargetSpecSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** The target profiles' files as the panel writes and finds them (`targets/<name>.yaml`, "Hesablar" and "Saytlar"). */
internal object TargetProfileFiles {
    private const val MAX_NAME = 60

    /** A profile name made from [site]'s host (`staging.shop.example` → `staging-shop-example`). */
    fun nameFor(site: URI): String =
        site.host
            .orEmpty()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_NAME)
            .trim('-')
            .ifEmpty { "site" }

    /**
     * The profile file in [directory] whose `target.name` is [name], whatever the file is called; null when there is
     * none. A profile that loads is matched by its name; one that does not (hand-edited since) by its `name:` line, so
     * a patch of it fails and is put back instead of a second profile of that name being written beside it.
     */
    fun named(
        directory: Path,
        name: String,
    ): Path? {
        if (!Files.isDirectory(directory)) return null
        val files =
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".yaml") || it.fileName.toString().endsWith(".yml") }.toList().sorted()
            }
        val source = YamlTargetSpecSource()
        val line = Regex("(?m)^\\s*name:\\s*['\"]?${Regex.escape(name)}['\"]?\\s*$")
        return files.firstOrNull { file -> runCatching { source.load(file).name == name }.getOrDefault(false) }
            ?: files.firstOrNull { file -> line.containsMatchIn(Files.readString(file)) }
    }
}
