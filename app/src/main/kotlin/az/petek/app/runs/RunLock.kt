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

package az.petek.app.runs

import az.petek.core.error.PetekException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One run at a time over one evidence store, whatever starts it: the panel (and `petek test`, MCP and the explorer's
 * session setups through it) or `petek run`, in this process or another (the owner's decision of 2026-09-30). A run
 * holds the operating system's lock on [file] (`<evidence>/run.lock`) while it goes, and the file says who holds it;
 * the lock goes with its process, so a run that crashed leaves none behind.
 *
 * A lock this process holds is answered from memory ([held]) without touching the file: the operating system's file
 * locks belong to the process, and closing any other descriptor of the file, even one opened only to read who holds
 * it, would release the running run's lock and let another process start over the same evidence.
 */
class RunLock(
    private val file: Path,
    private val now: () -> Instant = Instant::now,
) {
    /** Takes the lock for [holder] (what starts the run); fails with [RunLockBusyException] naming who holds it. */
    fun acquire(holder: String): AutoCloseable {
        val directory = Files.createDirectories(file.toAbsolutePath().parent)
        val key = directory.toRealPath().resolve(file.fileName)
        val text = "$holder, pid ${ProcessHandle.current().pid()}, since ${now()}"
        held.putIfAbsent(key, text)?.let { throw RunLockBusyException(it) }
        try {
            val channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock =
                try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    channel.close()
                    throw RunLockBusyException("another run of this process")
                }
            if (lock == null) {
                channel.close()
                // Held by another process: reading the file cannot drop a lock of this one, which holds none on it.
                throw RunLockBusyException(runCatching { Files.readString(key).trim() }.getOrDefault("").ifEmpty { "another process" })
            }
            channel.truncate(0)
            channel.write(ByteBuffer.wrap(text.toByteArray()), 0)
            return AutoCloseable {
                try {
                    runCatching { channel.truncate(0) }
                    lock.release()
                    channel.close()
                } finally {
                    held.remove(key, text)
                }
            }
        } catch (e: Throwable) {
            held.remove(key, text)
            throw e
        }
    }

    companion object {
        /** The lock's file in an evidence directory. */
        const val FILE_NAME = "run.lock"

        /** The locks this process holds, by the lock file's real path, with who holds each. */
        private val held = ConcurrentHashMap<Path, String>()
    }
}

/** Another run holds the [RunLock]: [holder] says who (what started it, its process, since when). */
class RunLockBusyException(
    val holder: String,
) : PetekException("Another run is going over this evidence store ($holder); wait for it to end.")
