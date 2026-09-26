/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepStatus
import az.petek.reporting.domain.FailureKeys
import az.petek.reporting.domain.ReportModel
import az.petek.reporting.domain.StabilityRow
import az.petek.reporting.domain.StepRow
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Azerbaijani labels and number formats shared by every report format, so Markdown and HTML always agree. */
internal object ReportFormat {
    const val NONE = "—"

    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

    // A link is only ever a relative path into the run directory: anything with a scheme, an absolute or
    // network path (`/x`, `//host`, `\\host`, which a browser may open as a UNC share) or a control character is dropped.
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val control = Regex("[\\u0000-\\u001f\\u007f]")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")

    fun title(model: ReportModel): String = "Pətək hesabatı: ${model.run.campaignName}"

    fun findingClass(value: FindingClass): String =
        when (value) {
            FindingClass.BACKEND -> "Backend xətası"
            FindingClass.DELIVERY_UI -> "Çatdırılma / UI xətası"
            FindingClass.INVESTIGATE -> "Araşdırılmalı"
            FindingClass.FLAKY -> "Qeyri-sabit (flaky)"
            FindingClass.AGENT_FAILURE -> "Agent xətası"
        }

    fun runResult(value: RunResult): String =
        when (value) {
            RunResult.RUNNING -> "davam edir"
            RunResult.PASSED -> "keçdi"
            RunResult.FAILED -> "keçmədi"
            RunResult.ABORTED -> "dayandırıldı"
        }

    /**
     * The row's result; a refusal the forbidden-action step expected ([FailureKeys.PERMISSION_DENIED]) is not "stuck",
     * and a lost race ([StepRow.lostRace]) is not a failure.
     */
    fun stepStatus(row: StepRow): String =
        if (row.lostRace) {
            "yarışı uduzdu"
        } else if (row.refused || isExpectedRefusal(row)) {
            "icazə verilmədi"
        } else {
            when (StepStatus.entries.firstOrNull { it.name == row.status }) {
                StepStatus.PASSED -> "keçdi"
                StepStatus.FAILED -> "keçmədi"
                StepStatus.SKIPPED -> "ötürüldü"
                StepStatus.BLOCKED -> "ilişdi"
                StepStatus.ERROR -> "xəta"
                null -> row.status.lowercase()
            }
        }

    /** Tone for colouring: `ok`, `bad` or `muted`. */
    fun stepTone(row: StepRow): String =
        when {
            row.lostRace -> "muted"
            row.status == StepStatus.PASSED.name -> "ok"
            row.status == StepStatus.SKIPPED.name || row.refused || isExpectedRefusal(row) -> "muted"
            else -> "bad"
        }

    private fun isExpectedRefusal(row: StepRow): Boolean =
        row.status == StepStatus.BLOCKED.name && FailureKeys.find(row.detail) == FailureKeys.PERMISSION_DENIED

    fun stability(row: StabilityRow): String =
        when {
            row.flaky -> "flaky"
            row.runs > 0 && row.passed == row.runs -> "sabit"
            else -> "həmişə keçmir"
        }

    fun agent(
        id: String?,
        name: String?,
    ): String =
        when {
            id == null -> "harness"
            name == null || name == id -> id
            else -> "$name ($id)"
        }

    /** The name the report already knows for [agentId] (step rows carry names from the agent directory). */
    fun agentName(
        model: ReportModel,
        agentId: String?,
    ): String? =
        agentId?.let { id ->
            model.steps.firstOrNull { it.agentId == id && it.agentName != null }?.agentName
                ?: model.failedAgents.firstOrNull { it.agentId == id }?.name
        }

    fun instant(value: Instant?): String = value?.let(timestamp::format) ?: NONE

    /** `812 ms`, `12,3 san`, `4 dəq 05 san`, `1 saat 02 dəq`. */
    fun duration(ms: Long): String {
        val seconds = ms / MS_PER_SECOND
        return when {
            ms < MS_PER_SECOND -> "$ms ms"
            ms < MS_PER_MINUTE -> String.format(Locale.ROOT, "%.1f san", ms / MS_PER_SECOND.toDouble()).replace('.', ',')
            ms < MS_PER_HOUR -> String.format(Locale.ROOT, "%d dəq %02d san", seconds / 60, seconds % 60)
            else -> String.format(Locale.ROOT, "%d saat %02d dəq", seconds / 3600, seconds / 60 % 60)
        }
    }

    fun latency(ms: Long?): String = ms?.let { "$it ms" } ?: NONE

    /** `5 000 000`: grouped so token counts stay readable. */
    fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value).replace(',', ' ')

    fun cost(usd: Double?): String = usd?.let { String.format(Locale.ROOT, "$%.4f", it) } ?: NONE

    fun percent(rate: Double): String = "${(rate * 100).roundToLong()}%"

    fun safeLink(link: String?): String? =
        link?.takeUnless {
            it.isBlank() || scheme.containsMatchIn(it) || it.startsWith('/') || '\\' in it || control.containsMatchIn(it)
        }

    fun isImage(link: String): Boolean = link.substringAfterLast('.', "").lowercase() in imageExtensions

    fun fileLabel(link: String): String = link.substringAfterLast('/')

    /**
     * Writes [content] to a temporary sibling and moves it into place, so a crash or a reader in the middle of a
     * rewrite (`petek report` on an existing run) never sees a half-written report.
     */
    fun writeFile(
        directory: Path,
        fileName: String,
        content: String,
    ): Path {
        Files.createDirectories(directory)
        val target = directory.resolve(fileName)
        val temporary = Files.createTempFile(directory, ".$fileName.", ".tmp")
        try {
            Files.writeString(temporary, content)
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private const val MS_PER_SECOND = 1_000L
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L
}
