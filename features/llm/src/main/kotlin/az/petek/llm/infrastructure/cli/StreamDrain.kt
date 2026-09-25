package az.petek.llm.infrastructure.cli

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Drains a process pipe to EOF (a full pipe would block the child) while keeping memory bounded.
 * An I/O error, e.g. the pipe closing because the process was killed, ends the read with what arrived so far.
 */
internal object StreamDrain {
    private const val BUFFER_SIZE = 8 * 1024

    /** Keeps the first [limit] bytes and discards the rest. */
    fun head(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val kept = ByteArrayOutputStream()
        drain(input) { buffer, count ->
            val room = limit - kept.size()
            if (room > 0) kept.write(buffer, 0, minOf(room, count))
        }
        return kept.toByteArray()
    }

    /** Keeps the last [limit] bytes (the end of stderr is where a failing CLI explains itself). */
    fun tail(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        var kept = ByteArrayOutputStream()
        drain(input) { buffer, count ->
            kept.write(buffer, 0, count)
            if (kept.size() > 2 * limit) kept = lastBytes(kept.toByteArray(), limit)
        }
        return lastBytes(kept.toByteArray(), limit).toByteArray()
    }

    private fun lastBytes(
        bytes: ByteArray,
        limit: Int,
    ): ByteArrayOutputStream {
        val from = (bytes.size - limit).coerceAtLeast(0)
        return ByteArrayOutputStream().apply { write(bytes, from, bytes.size - from) }
    }

    private inline fun drain(
        input: InputStream,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            input.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    onChunk(buffer, count)
                }
            }
        } catch (_: IOException) {
            // The pipe was closed under us (process killed); keep what was read.
        }
    }
}
