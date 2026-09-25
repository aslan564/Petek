/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.llm.infrastructure.cli

import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reads what a process wrote to its output files with bounded memory. A file that was never created (the process
 * did not start far enough) reads as empty.
 */
internal object OutputFiles {
    /** The first [limit] bytes. */
    fun head(
        file: Path,
        limit: Int,
    ): ByteArray = readOrEmpty(file) { Files.newInputStream(file).use { it.readNBytes(limit) } }

    /** The last [limit] bytes (the end of stderr is where a failing CLI explains itself). */
    fun tail(
        file: Path,
        limit: Int,
    ): ByteArray =
        readOrEmpty(file) {
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                channel.position((channel.size() - limit).coerceAtLeast(0))
                Channels.newInputStream(channel).readNBytes(limit)
            }
        }

    private inline fun readOrEmpty(
        file: Path,
        read: () -> ByteArray,
    ): ByteArray =
        try {
            if (Files.exists(file)) read() else ByteArray(0)
        } catch (_: NoSuchFileException) {
            ByteArray(0)
        }
}
