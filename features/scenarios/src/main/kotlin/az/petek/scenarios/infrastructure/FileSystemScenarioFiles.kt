package az.petek.scenarios.infrastructure

import az.petek.scenarios.domain.ScenarioFileException
import az.petek.scenarios.domain.ScenarioFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Scenario files on the local disk (blocking I/O on [Dispatchers.IO]). Reading is strict UTF-8, like the campaign
 * loader. Writing is exact: without `overwrite` an existing file is never touched (atomic create); with it the new
 * text is written to a sibling temporary file and moved over the old one, so a reader never sees half a file.
 */
class FileSystemScenarioFiles : ScenarioFiles {
    override suspend fun read(path: Path): String =
        withContext(Dispatchers.IO) {
            val bytes =
                try {
                    Files.readAllBytes(path)
                } catch (e: NoSuchFileException) {
                    throw ScenarioFileException(path, "does not exist", e)
                } catch (e: IOException) {
                    throw ScenarioFileException(path, "cannot be read: ${e.message ?: e.javaClass.simpleName}", e)
                }
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw ScenarioFileException(path, "is not valid UTF-8", e)
            }
        }

    override suspend fun write(
        path: Path,
        text: String,
        overwrite: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            try {
                path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                if (overwrite) replace(path, bytes) else Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (e: FileAlreadyExistsException) {
                throw ScenarioFileException(path, "already exists; pass overwrite to replace it", e)
            } catch (e: IOException) {
                throw ScenarioFileException(path, "cannot be written: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    private fun replace(
        path: Path,
        bytes: ByteArray,
    ) {
        val absolute = path.toAbsolutePath()
        val temporary = Files.createTempFile(absolute.parent, ".${absolute.fileName}", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
