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
