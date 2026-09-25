package az.petek.evidence.infrastructure

import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.ids.UuidV7IdGenerator
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Stores evidence files under `<root>/<runId>/<owner>/<seq>-<type>.<ext>`, e.g. `run_…/a07/0003-screenshot.png`,
 * and returns their [ArtifactRecord] (the caller records it with the evidence recorder).
 *
 * - `seq` is a per-(run, owner) counter, safe under concurrent writers. It continues after the files already on
 *   disk, and a file name that is already taken is skipped, so earlier evidence is never overwritten (by a
 *   restarted process, or by a second store over the same root). One store per root and process is expected: two
 *   stores racing for the very same name in the same instant remain the only case the check cannot close.
 * - Each file is written to a temporary sibling and then moved into place with an atomic move, so readers see
 *   either nothing or the complete file. Blocking I/O runs on [Dispatchers.IO].
 * - `owner` is sanitized to `[A-Za-z0-9_-]` and run ids must be safe directory names; [resolve] rejects any
 *   recorded path that would leave [root].
 *
 * @param root the evidence root directory; created on demand.
 * @param ids source of artifact ids (inject the process-wide generator; tests pass a deterministic one).
 */
class FileSystemArtifactStore(
    root: Path,
    private val ids: IdGenerator = UuidV7IdGenerator(),
) : ArtifactStore {
    private val root: Path = root.toAbsolutePath().normalize()
    private val counters = ConcurrentHashMap<Path, AtomicInteger>()

    override suspend fun write(
        runId: RunId,
        stepId: StepId,
        owner: String,
        type: ArtifactType,
        bytes: ByteArray,
    ): ArtifactRecord =
        withContext(Dispatchers.IO) {
            val runDirectory = ArtifactPaths.requireSafeRunId(runId)
            val ownerDirectory = ArtifactPaths.sanitizeOwner(owner)
            val directory = root.resolve(runDirectory).resolve(ownerDirectory)
            Files.createDirectories(directory)
            val fileName = nextFreeFileName(directory, type)
            writeAtomically(directory, fileName, bytes)
            ArtifactRecord(
                artifactId = ids.artifactId(),
                runId = runId,
                stepId = stepId,
                type = type,
                relativePath = ArtifactPaths.relativePath(runDirectory, ownerDirectory, fileName),
                sha256 = sha256Hex(bytes),
                sizeBytes = bytes.size.toLong(),
            )
        }

    override fun resolve(record: ArtifactRecord): Path = ArtifactPaths.resolveInside(root, record.relativePath)

    override fun runDirectory(runId: RunId): Path = root.resolve(ArtifactPaths.requireSafeRunId(runId))

    /**
     * The next sequence number whose file name is still free. An atomic move silently replaces an existing target
     * on POSIX, so a name that is already taken (by another store over the same root, or by an owner directory that
     * a case-insensitive file system folds into this one) is skipped instead of overwritten.
     */
    private fun nextFreeFileName(
        directory: Path,
        type: ArtifactType,
    ): String {
        while (true) {
            val fileName = ArtifactPaths.fileName(nextSeq(directory), type)
            // exists() is false when existence cannot be determined; the write then fails with the real I/O error.
            if (!directory.resolve(fileName).exists(LinkOption.NOFOLLOW_LINKS)) return fileName
        }
    }

    /** The first call per directory scans it once, so numbering resumes after files written by earlier processes. */
    private fun nextSeq(directory: Path): Int =
        counters.computeIfAbsent(directory) { AtomicInteger(highestSeqOnDisk(it)) }.incrementAndGet()

    private fun highestSeqOnDisk(directory: Path): Int =
        if (directory.exists()) {
            directory.listDirectoryEntries().maxOfOrNull { ArtifactPaths.seqOf(it.name) ?: 0 } ?: 0
        } else {
            0
        }

    private fun writeAtomically(
        directory: Path,
        fileName: String,
        bytes: ByteArray,
    ) {
        val temporary = directory.resolve(".$fileName.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temporary, directory.resolve(fileName), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            // Clean up, but never let a failed cleanup hide the error that explains the lost artifact.
            runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
