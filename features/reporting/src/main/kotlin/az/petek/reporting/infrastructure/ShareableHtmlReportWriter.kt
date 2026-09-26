/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.evidence.domain.EvidenceTier
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.ReportWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * The HTML report as one file to send around (`report/share.html`, Faza 12): the page of [HtmlReportWriter] with every
 * screenshot embedded as a `data:` image, so it opens anywhere without the evidence directory, and a header line naming
 * the AI that drove the testers ([aiProvider], [aiModel]) and how the findings' proof splits by evidence tier.
 * Images outside the run's directory, or larger than [maxImageBytes], stay links.
 */
class ShareableHtmlReportWriter(
    private val aiProvider: String?,
    private val aiModel: String?,
    private val maxImageBytes: Long = 2L * 1024 * 1024,
) : ReportWriter {
    override val fileName: String = "share.html"

    private val base = HtmlReportWriter()

    override fun write(
        model: ReportModel,
        directory: Path,
    ): Path = ReportFormat.writeFile(directory, fileName, render(model, directory))

    fun render(
        model: ReportModel,
        directory: Path,
    ): String {
        val page = base.render(model)
        val withImages =
            IMAGE.replace(page) { match ->
                val link = match.groupValues[2]
                val data = embed(directory, link)
                if (data == null) match.value else match.groupValues[1] + data + "\""
            }
        return withImages.replaceFirst("<ul class=\"meta\">", "<ul class=\"meta\">" + header(model))
    }

    private fun header(model: ReportModel): String {
        val tiers =
            EvidenceTier.entries
                .map { tier -> tier to model.findings.count { it.evidenceTier == tier } }
                .filter { it.second > 0 }
                .joinToString(", ") { (tier, count) -> "${ReportFormat.evidenceTier(tier)}: $count" }
                .ifEmpty { "tapıntı yoxdur" }
        val ai = listOfNotNull(aiProvider, aiModel).joinToString(" · ").ifEmpty { "naməlum" }
        return "<li>${escape("AI: $ai")}</li><li>${escape("Sübut səviyyələri: $tiers")}</li>"
    }

    /** A `data:` URI for the image [link] (relative to [directory]) when it stays inside the run and is small enough. */
    private fun embed(
        directory: Path,
        link: String,
    ): String? {
        val extension = link.substringAfterLast('.', "").lowercase()
        val mime = MIME[extension] ?: return null
        val runDirectory = directory.toAbsolutePath().normalize().parent ?: return null
        val file = directory.resolve(link).toAbsolutePath().normalize()
        if (!file.startsWith(runDirectory) || !Files.isRegularFile(file) || Files.size(file) > maxImageBytes) return null
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file))
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        val IMAGE = Regex("(<img[^>]*?src=\")([^\"]+)\"")
        val MIME = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")
    }
}
