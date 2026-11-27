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

package az.petek.reporting.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Puts a report page's screenshots inside it as `data:` images, so the page needs nothing beside it. */
internal object ReportImages {
    /**
     * [page] (written in [directory], a run's `report/` folder) with every `<img>` it links to embedded, as long as the
     * image stays inside the run's directory and is at most [maxImageBytes]; any other image stays a link.
     */
    fun embed(
        page: String,
        directory: Path,
        maxImageBytes: Long,
    ): String =
        IMAGE.replace(page) { match ->
            val data = dataOf(directory, match.groupValues[2], maxImageBytes)
            if (data == null) match.value else match.groupValues[1] + data + "\""
        }

    /** A `data:` URI for the image [link] (relative to [directory]) when it stays inside the run and is small enough. */
    private fun dataOf(
        directory: Path,
        link: String,
        maxImageBytes: Long,
    ): String? {
        val extension = link.substringAfterLast('.', "").lowercase()
        val mime = MIME[extension] ?: return null
        val runDirectory = directory.toAbsolutePath().normalize().parent ?: return null
        val file = directory.resolve(link).toAbsolutePath().normalize()
        if (!file.startsWith(runDirectory) || !Files.isRegularFile(file) || Files.size(file) > maxImageBytes) return null
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file))
    }

    private val IMAGE = Regex("(<img[^>]*?src=\")([^\"]+)\"")
    private val MIME =
        mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")
}
