/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.domain.PanelBackend
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Files the panel serves, never by a path the client chose:
 * - `GET /artifacts/{artifactId}` — a recorded artifact of the run on the board or of the current exploration, found
 *   through the records the dashboard (or the backend) vouches for; immutable, so cached;
 * - `GET /report/...` — the shown run's report directory, and `GET /{owner}/{file}` for the report's relative evidence
 *   links (`../a07/0003-screenshot.png`), answered only with a recorded artifact of that path;
 * - `GET /runs/{runId}/report/...` — the report of any run the backend knows, and `GET /runs/{runId}/{owner}/{file}` for
 *   its evidence links, read from that run's evidence directory with the same checks as the report files.
 */
internal fun Route.fileRoutes(
    dashboard: LiveDashboard,
    artifacts: ArtifactStore,
    backend: PanelBackend,
    reportDirectory: () -> Path?,
) {
    get("/artifacts/{artifactId}") {
        val id = call.parameters["artifactId"]?.takeIf(PLAIN_ID::matches)?.let(::ArtifactId)
        val record = id?.let { dashboard.artifact(it) ?: backend.explorationArtifact(it) }
        if (record == null) return@get call.notFound()
        call.respondArtifact(artifacts, record)
    }
    get("/report") { call.respondRedirect(DashboardJson.REPORT_URL) }
    get("/report/{path...}") {
        val root = reportDirectory()
        if (root == null) return@get call.notFound("Hesabat hələ hazır deyil.")
        call.respondReportFile(root, call.parameters.getAll("path").orEmpty())
    }
    // The report links its evidence relatively (`../a07/0003-screenshot.png`), which resolves to this route.
    get("/{owner}/{file}") {
        val owner = call.parameters["owner"]?.takeIf(OWNER::matches)
        val file = call.parameters["file"]?.takeIf(FILE::matches)
        val record = if (owner == null || file == null) null else dashboard.artifactAt("$owner/$file")
        if (record == null) return@get call.notFound()
        call.respondArtifact(artifacts, record)
    }
    get("/runs/{runId}/report") { call.respondRedirect("/runs/${call.parameters["runId"]?.takeIf(PLAIN_ID::matches) ?: ""}/report/") }
    get("/runs/{runId}/report/{path...}") {
        val root = call.historyRun()?.let { backend.reportDirectory(it) }
        if (root == null) return@get call.notFound("Bu run üçün hesabat yoxdur.")
        call.respondReportFile(root, call.parameters.getAll("path").orEmpty())
    }
    get("/runs/{runId}/{owner}/{file}") {
        val runId = call.historyRun()?.takeIf { backend.reportDirectory(it) != null }
        val owner = call.parameters["owner"]?.takeIf(OWNER::matches)
        val file = call.parameters["file"]?.takeIf(FILE::matches)
        val path =
            if (runId == null || owner == null || file == null) {
                null
            } else {
                withContext(Dispatchers.IO) { ReportFiles.resolve(artifacts.runDirectory(runId), listOf(owner, file)) }
            }
        if (path == null) return@get call.notFound()
        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
        call.response.header(CONTENT_SECURITY_POLICY, ARTIFACT_POLICY)
        call.respond(LocalPathContent(path, ArtifactContent.forFile(path)))
    }
}

private val PLAIN_ID = Regex("[A-Za-z0-9_-]{1,128}")
private val OWNER = Regex("[A-Za-z0-9_-]{1,64}")
private val FILE = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")
private const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"

/** Captured text and pictures are shown, never run: no script, no plugin, no navigation. */
private const val ARTIFACT_POLICY = "default-src 'none'; style-src 'unsafe-inline'; sandbox"
private const val REPORT_POLICY =
    "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

private fun ApplicationCall.historyRun(): RunId? = parameters["runId"]?.takeIf(PLAIN_ID::matches)?.let(::RunId)

private suspend fun ApplicationCall.respondReportFile(
    root: Path,
    segments: List<String>,
) {
    val file = withContext(Dispatchers.IO) { ReportFiles.resolve(root, segments) } ?: return notFound()
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header(CONTENT_SECURITY_POLICY, REPORT_POLICY)
    respond(LocalPathContent(file, ReportFiles.contentType(file)))
}

private suspend fun ApplicationCall.respondArtifact(
    artifacts: ArtifactStore,
    record: ArtifactRecord,
) {
    val file =
        withContext(Dispatchers.IO) {
            try {
                artifacts.resolve(record).takeIf { Files.isRegularFile(it) }
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return notFound()
    val tag = "\"${record.sha256}\""
    response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
    response.header(HttpHeaders.ETag, tag)
    response.header(CONTENT_SECURITY_POLICY, ARTIFACT_POLICY)
    if (request.headers[HttpHeaders.IfNoneMatch] == tag) return respond(HttpStatusCode.NotModified)
    respond(LocalPathContent(file, ArtifactContent.type(record.type)))
}

internal suspend fun ApplicationCall.notFound(message: String = "Tapılmadı.") {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(message, status = HttpStatusCode.NotFound)
}
