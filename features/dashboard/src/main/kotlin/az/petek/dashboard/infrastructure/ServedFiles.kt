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

package az.petek.dashboard.infrastructure

import az.petek.evidence.domain.ArtifactType
import io.ktor.http.ContentType
import io.ktor.http.withCharset
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * How recorded artifacts are served. Anything captured from the target (DOM, accessibility tree, HTTP bodies) is text
 * to read, never markup to render: serving a captured page as HTML from the dashboard's origin would run the target's
 * scripts next to the dashboard.
 */
internal object ArtifactContent {
    fun type(type: ArtifactType): ContentType =
        when (type) {
            ArtifactType.SCREENSHOT -> ContentType.Image.PNG
            ArtifactType.MAIL, ArtifactType.ORACLE -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
            ArtifactType.A11Y, ArtifactType.DOM, ArtifactType.HTTP, ArtifactType.PROMPT, ArtifactType.LOG -> TEXT
        }

    /** For a file of a run's evidence directory: pictures and JSON as such, everything else (HTML too) as plain text. */
    fun forFile(file: Path): ContentType =
        when (file.extension.lowercase()) {
            "png" -> ContentType.Image.PNG
            "json" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
            else -> TEXT
        }

    private val TEXT = ContentType.Text.Plain.withCharset(Charsets.UTF_8)
}

/**
 * Serves files of the run's report directory (`index.html`, `report.md`, anything the writers put there) and nothing
 * outside it. A request names path segments, never a path: every segment must be a plain, visible name (no `..`, no
 * separators, no leading dot), the joined path must stay inside the directory, and so must its real path once symbolic
 * links are followed. Blocking: call on an I/O dispatcher.
 */
internal object ReportFiles {
    private const val INDEX = "index.html"
    private const val MARKDOWN = "report.md"
    private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")

    /** The file for [segments] inside [root], or null when there is none or the request tries to leave [root]. */
    fun resolve(
        root: Path,
        segments: List<String>,
    ): Path? {
        val parts = segments.filter { it.isNotEmpty() }
        if (parts.any { !SEGMENT.matches(it) || it.contains("..") }) return null
        val base = root.toAbsolutePath().normalize()
        if (parts.isEmpty()) return listOf(INDEX, MARKDOWN).firstNotNullOfOrNull { inside(base, base.resolve(it)) }
        return inside(base, base.resolve(parts.joinToString("/")).normalize())
    }

    fun contentType(file: Path): ContentType =
        when (file.extension.lowercase()) {
            "html", "htm" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
            "md" -> ContentType("text", "markdown").withCharset(Charsets.UTF_8)
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            "json" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "svg" -> ContentType.Image.SVG
            else -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
        }

    private fun inside(
        base: Path,
        candidate: Path,
    ): Path? {
        if (!candidate.startsWith(base) || candidate == base || !candidate.isRegularFile()) return null
        return try {
            val real = candidate.toRealPath()
            if (real.startsWith(base.toRealPath()) && Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) real else null
        } catch (_: IOException) {
            null
        }
    }
}
