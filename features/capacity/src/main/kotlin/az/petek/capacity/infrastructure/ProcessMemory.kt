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

package az.petek.capacity.infrastructure

import az.petek.capacity.domain.Bytes
import az.petek.capacity.domain.CapacityMeasurementException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines

/** Memory used by the processes this JVM started (browser servers, Playwright drivers, Chromium and its helpers). */
internal fun interface ProcessMemory {
    /** Bytes in use by every descendant process of this JVM right now. Blocking; call it on [kotlinx.coroutines.Dispatchers.IO]. */
    fun descendantBytes(): Long
}

/**
 * [ProcessMemory] from Linux `/proc`. Each process counts with its proportional set size (`Pss` of
 * `/proc/<pid>/smaps_rollup`): Chromium's processes share much of their memory, and summing resident sizes would count
 * the shared pages once per process and overstate the cost several times. Where `smaps_rollup` is not readable (older
 * kernels), `VmRSS` of `/proc/<pid>/status` is used, which errs on the safe side. A process that ends while being read
 * counts as 0.
 */
internal class ProcProcessMemory(
    private val procRoot: Path = Path.of("/proc"),
    private val pids: () -> List<Long> = {
        ProcessHandle
            .current()
            .descendants()
            .map { it.pid() }
            .toList()
    },
) : ProcessMemory {
    override fun descendantBytes(): Long {
        if (!procRoot.isDirectory()) {
            throw CapacityMeasurementException("process memory cannot be measured here: $procRoot is not available")
        }
        return pids().sumOf(::bytesOf)
    }

    private fun bytesOf(pid: Long): Long {
        val directory = procRoot.resolve(pid.toString())
        return kilobytes(directory.resolve("smaps_rollup"), "Pss")
            ?: kilobytes(directory.resolve("status"), "VmRSS")
            ?: 0
    }

    /** The `<field>:  <n> kB` value of [file] in bytes, or null when the file or field cannot be read. */
    private fun kilobytes(
        file: Path,
        field: String,
    ): Long? {
        val lines =
            try {
                if (!Files.isReadable(file)) return null
                file.readLines()
            } catch (e: IOException) {
                return null
            }
        val prefix = "$field:"
        return lines
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
            ?.times(Bytes.KIB)
    }
}
