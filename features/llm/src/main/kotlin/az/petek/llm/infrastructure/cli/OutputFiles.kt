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
