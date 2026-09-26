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
 * [TraceSource] over the target's own log file (`PETEK_TRACE_LOG`, Faza 14): the lines containing a correlation id,
 * at most [maxLines], each cut to [maxLineChars]. A missing or unreadable file yields nothing, never a failure.
 */
class LogFileTraceSource(
    private val file: Path,
    private val maxLines: Int = 50,
    private val maxLineChars: Int = 2_000,
) : TraceSource {
    override suspend fun lines(correlationId: String): List<String> {
        if (correlationId.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                Files.newBufferedReader(file).useLines { lines ->
                    lines
                        .filter { correlationId in it }
                        .map { it.take(maxLineChars) }
                        .take(maxLines)
                        .toList()
                }
            } catch (_: IOException) {
                emptyList()
            }
        }
    }
}
