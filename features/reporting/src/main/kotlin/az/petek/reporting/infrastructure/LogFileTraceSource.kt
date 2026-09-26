/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import az.petek.reporting.domain.TraceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [TraceSource] over the target's own log file (`PETEK_TRACE_LOG`, Faza 14): the lines carrying a correlation id as a
 * whole token (`cor_ab` never matches inside `cor_abc`), at most [maxLines], each cut to [maxLineChars]. The lines go
 * into reports and finding bundles, so they are redacted first: the [secrets] Pətək knows, and what server logs
 * typically carry (authorization headers, cookies, bearer and JSON web tokens, `password`/`token`/`secret` values).
 * A missing or unreadable file yields nothing, never a failure.
 */
class LogFileTraceSource(
    private val file: Path,
    private val maxLines: Int = 50,
    private val maxLineChars: Int = 2_000,
    private val secrets: List<String> = emptyList(),
) : TraceSource {
    override suspend fun lines(correlationId: String): List<String> {
        if (correlationId.isBlank()) return emptyList()
        val whole = Regex("(?<![A-Za-z0-9_-])" + Regex.escape(correlationId) + "(?![A-Za-z0-9_-])")
        return withContext(Dispatchers.IO) {
            try {
                Files.newBufferedReader(file).useLines { lines ->
                    lines
                        .filter { whole.containsMatchIn(it) }
                        .map { redact(it).take(maxLineChars) }
                        .take(maxLines)
                        .toList()
                }
            } catch (_: IOException) {
                emptyList()
            }
        }
    }

    /** The line with every known secret and every typical credential shape replaced by `***`. */
    fun redact(line: String): String {
        var out = line
        secrets.filter { it.length >= MIN_SECRET }.forEach { out = out.replace(it, MASK) }
        PATTERNS.forEach { (pattern, keep) -> out = pattern.replace(out) { "${it.groupValues[keep]}$MASK" } }
        return out
    }

    private companion object {
        const val MASK = "***"
        const val MIN_SECRET = 4

        /** Credential shapes; the group kept is the part before the secret (its name), 0 keeps nothing. */
        val PATTERNS: List<Pair<Regex, Int>> =
            listOf(
                Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic|token)?\\s*[^\\s,;\"']+") to 1,
                Regex("(?i)((?:set-)?cookie\\s*[:=]\\s*)[^\\r\\n]+") to 1,
                Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+") to 1,
                Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}") to 0,
                Regex("(?i)(\"?(?:password|passwd|pwd|secret|token|api[_-]?key|x-test-token)\"?\\s*[:=]\\s*\"?)[^\"\\s,;&}]+") to 1,
            )
    }
}
