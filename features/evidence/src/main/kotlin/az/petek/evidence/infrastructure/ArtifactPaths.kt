package az.petek.evidence.infrastructure

import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactType
import java.nio.file.Path

/**
 * Naming rules of the artifact tree `<root>/<runId>/<owner>/<seq>-<type>.<ext>`. Nothing that reaches a path
 * segment may climb out of the evidence root: owners are sanitized, run ids are validated, and resolved paths are
 * checked against the root.
 */
internal object ArtifactPaths {
    /** Longest owner directory name; owners are agent ids (`a07`) or harness components in practice. */
    const val MAX_OWNER_LENGTH = 64

    private const val SEQ_DIGITS = 4
    private val OWNER_FORBIDDEN = Regex("[^A-Za-z0-9_-]")
    private val RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
    private val FILE_SEQ = Regex("^(\\d+)-")

    /**
     * Maps any owner to a safe directory name made of `[A-Za-z0-9_-]`: every other character (including `.`, `/`
     * and `\`) becomes `_`, so `..` can never appear. Blank owners map to `_`.
     */
    fun sanitizeOwner(owner: String): String = owner.take(MAX_OWNER_LENGTH).replace(OWNER_FORBIDDEN, "_").ifEmpty { "_" }

    /** A run id becomes a directory name as is, so it must be a single, non-hidden, traversal-free segment. */
    fun requireSafeRunId(runId: RunId): String {
        require(RUN_ID.matches(runId.value)) { "Run id '${runId.value}' cannot be used as an evidence directory name" }
        return runId.value
    }

    /** `0003-screenshot.png`; the sequence is zero-padded to four digits (and simply grows past 9999). */
    fun fileName(
        seq: Int,
        type: ArtifactType,
    ): String = "${seq.toString().padStart(SEQ_DIGITS, '0')}-${type.name.lowercase()}.${type.extension}"

    /** The sequence number a file name starts with, or null for anything else (temporary files, strays). */
    fun seqOf(fileName: String): Int? =
        FILE_SEQ
            .find(fileName)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /** Evidence-root-relative path with `/` separators on every platform, as stored in the database. */
    fun relativePath(
        runDirectory: String,
        owner: String,
        fileName: String,
    ): String = "$runDirectory/$owner/$fileName"

    /**
     * Resolves [relativePath] against [root] (absolute and normalized) and rejects anything that is absolute or
     * does not end up strictly inside [root].
     */
    fun resolveInside(
        root: Path,
        relativePath: String,
    ): Path {
        val relative = Path.of(relativePath)
        require(!relative.isAbsolute) { "Artifact path '$relativePath' must be relative to the evidence root" }
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root) && resolved != root) { "Artifact path '$relativePath' escapes the evidence root" }
        return resolved
    }
}
